package com.sample.data_scrapper.service;

import com.sample.data_scrapper.config.ProductDataProperties;
import com.sample.data_scrapper.dto.ProductDataDto;
import com.sample.data_scrapper.dto.TomfordProductDto;
import com.sample.data_scrapper.entity.BrandsiteProductExtraction;
import com.sample.data_scrapper.entity.KingpowerProductExtraction;
import com.sample.data_scrapper.repository.BrandsiteProductExtractionRepository;
import com.sample.data_scrapper.repository.KingpowerProductExtractionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Processes CSV-derived product data from Kingpower and Tomford files: filters Tomford to products present in Kingpower,
 * fetches details via LLM for Kingpower products missing in Tomford and maps them to TomfordProductDto,
 * applies LLM restructure to product details, then persists to kingpower_product_extractions and brandsite_product_extractions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductDataService {

    private final GeminiProductService geminiProductService;
    private final KingpowerProductExtractionRepository kingpowerRepository;
    private final BrandsiteProductExtractionRepository brandsiteRepository;
    private final ProductComparisonService productComparisonService;
    private final ProductDataCsvService csvService;
    private final ProductDataProperties productDataProperties;
    private final List<ProductDataDto> lastParsedProducts = new CopyOnWriteArrayList<>();
    private volatile String lastProcessedFileName;

    /**
     * Main workflow: read Kingpower_data.csv and Tomford_data(2).csv from the watch directory,
     * map to respective DTOs, filter Tomford to only products whose name exists in Kingpower,
     * restructure product descriptions via LLM, then save all data to respective database tables.
     * Product details in DB are the LLM-restructured text; all other fields are saved as extracted.
     */
    @Transactional
    public void runKingpowerAndTomfordWorkflow() {
        Path watchDir = Paths.get(productDataProperties.getWatchDirectory());
        Path kingpowerPath = watchDir.resolve(productDataProperties.getKingpowerFileName());
        Path tomfordPath = watchDir.resolve(productDataProperties.getTomfordFileName());

        if (!Files.isRegularFile(kingpowerPath)) {
            log.warn("Kingpower CSV not found: {}. Skipping two-file workflow.", kingpowerPath);
            return;
        }
        if (!Files.isRegularFile(tomfordPath)) {
            log.warn("Tomford CSV not found: {}. Skipping two-file workflow.", tomfordPath);
            return;
        }

        List<ProductDataDto> kingpowerList = csvService.parseCsvFile(kingpowerPath);
        List<TomfordProductDto> tomfordList = csvService.parseTomfordCsv(tomfordPath);

        Set<String> kingpowerCoreNames = kingpowerList.stream()
                .map(ProductDataDto::getName)
                .filter(Objects::nonNull)
                .map(this::normalizeProductNameForComparison)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        List<TomfordProductDto> filteredTomford = tomfordList.stream()
                .filter(t -> matchesAnyKingpowerProduct(normalizeProductNameForComparison(t.getProductName()), kingpowerCoreNames))
                .toList();
        int removed = tomfordList.size() - filteredTomford.size();
        if (removed > 0) {
            log.info("" +
                    "Filtered Tomford: kept {} products that exist in Kingpower, removed {}", filteredTomford.size(), removed);
        }
        if (log.isDebugEnabled() && !kingpowerCoreNames.isEmpty()) {
            log.debug("Kingpower core names (for comparison): {} | Tomford sample: {}",
                    kingpowerCoreNames,
                    tomfordList.stream().limit(3).map(t -> normalizeProductNameForComparison(t.getProductName())).toList());
        }

        // Products that exist in Kingpower but have no matching Tomford row: fetch details via LLM and map to TomfordProductDto
        Set<String> tomfordNormalizedNames = filteredTomford.stream()
                .map(t -> normalizeProductNameForComparison(t.getProductName()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<ProductDataDto> missingInTomford = kingpowerList.stream()
                .filter(kp -> {
                    String norm = normalizeProductNameForComparison(kp.getName());
                    return norm != null && !norm.isBlank() && !tomfordNormalizedNames.stream().anyMatch(tn -> tn != null && (tn.equals(norm) || tn.contains(norm) || norm.contains(tn)));
                })
                .toList();
        // Deduplicate by normalized name so we call LLM once per product name
        List<ProductDataDto> missingUnique = missingInTomford.stream()
                .filter(distinctByKey(kp -> normalizeProductNameForComparison(kp.getName())))
                .toList();

        List<TomfordProductDto> tomfordFromLlm = new ArrayList<>();
        long delayMs = productDataProperties.getLlmFetchDelayMs() > 0 ? productDataProperties.getLlmFetchDelayMs() : 0;
        for (int i = 0; i < missingUnique.size(); i++) {
            if (i > 0 && delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("LLM fetch delay interrupted");
                    break;
                }
            }
            ProductDataDto kp = missingUnique.get(i);
            geminiProductService.fetchProductDetailsForMissingProduct(
                    kp.getName(),
                    kp.getBrand(),
                    kp.getDescription()
            ).ifPresent(details -> tomfordFromLlm.add(toTomfordProductDtoFromLlm(kp, details)));
        }
        if (!tomfordFromLlm.isEmpty()) {
            log.info("Fetched {} product details via LLM for products missing in Tomford and mapped to TomfordProductDto", tomfordFromLlm.size());
        }

        List<TomfordProductDto> combinedTomford = deduplicateTomfordForPersistence(filteredTomford, tomfordFromLlm);
        if (combinedTomford.size() < filteredTomford.size() + tomfordFromLlm.size()) {
            log.info("Deduplicated brandsite batch: {} unique products (removed {} duplicate rows by brand+normalized name)",
                    combinedTomford.size(),
                    filteredTomford.size() + tomfordFromLlm.size() - combinedTomford.size());
        }

        lastParsedProducts.clear();
        lastProcessedFileName = productDataProperties.getKingpowerFileName();

        for (ProductDataDto dto : kingpowerList) {
            String restructured = geminiProductService.restructureProductDetails(
                    dto.getDescription(),
                    dto.getName(),
                    dto.getBrand()
            );
            dto.setRestructuredProductDetails(restructured);
            lastParsedProducts.add(dto);
        }
        List<KingpowerProductExtraction> kingpowerEntities = kingpowerList.stream()
                .map(this::toKingpowerProductExtraction)
                .toList();
        kingpowerRepository.saveAll(kingpowerEntities);
        log.info("Saved {} rows to kingpower_product_extractions (product_details = LLM-restructured)", kingpowerEntities.size());

        for (TomfordProductDto dto : combinedTomford) {
            String restructured = geminiProductService.restructureProductDetails(
                    dto.getDescription(),
                    dto.getProductName(),
                    dto.getBrand()
            );
            dto.setRestructuredProductDetails(restructured);
        }
        List<BrandsiteProductExtraction> tomfordEntities = combinedTomford.stream()
                .map(this::toBrandsiteProductExtraction)
                .toList();
        if (productDataProperties.isBrandsiteReplaceByBrandOnRun() && !tomfordEntities.isEmpty()) {
            Set<String> brands = tomfordEntities.stream()
                    .map(BrandsiteProductExtraction::getBrand)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            for (String brand : brands) {
                brandsiteRepository.deleteByBrand(brand);
            }
            if (!brands.isEmpty()) {
                log.info("Replaced existing brandsite_product_extractions for brand(s): {}", brands);
            }
        }
        brandsiteRepository.saveAll(tomfordEntities);
        log.info("Saved {} rows to brandsite_product_extractions (product_details = LLM-restructured)", tomfordEntities.size());

        int compared = productComparisonService.runComparisonWorkflow();
        log.info("Comparison workflow completed: {} rows saved to compared_product_data.", compared);
    }

    /**
     * One row per logical product: same brand + normalized product name appears only once.
     * CSV-sourced Tomford rows win over LLM-filled rows; within CSV, first row wins.
     */
    private List<TomfordProductDto> deduplicateTomfordForPersistence(
            List<TomfordProductDto> filteredTomford,
            List<TomfordProductDto> tomfordFromLlm) {
        Map<String, TomfordProductDto> byKey = new LinkedHashMap<>();
        for (TomfordProductDto t : filteredTomford) {
            byKey.putIfAbsent(tomfordDedupeKey(t), t);
        }
        for (TomfordProductDto t : tomfordFromLlm) {
            byKey.putIfAbsent(tomfordDedupeKey(t), t);
        }
        return new ArrayList<>(byKey.values());
    }

    private String tomfordDedupeKey(TomfordProductDto t) {
        String norm = normalizeProductNameForComparison(t.getProductName());
        String brand = t.getBrand() != null ? t.getBrand().trim().toLowerCase(Locale.ROOT) : "";
        return brand + "|" + (norm != null ? norm : "");
    }

    /**
     * Builds a TomfordProductDto from a ProductDataDto (missing in Tomford) and LLM-fetched details.
     */
    private TomfordProductDto toTomfordProductDtoFromLlm(ProductDataDto kp, Map<String, String> llmDetails) {
        String description = nullToEmpty(llmDetails.get("description"));
        if (description.isBlank() && kp.getDescription() != null) {
            description = kp.getDescription();
        }
        return TomfordProductDto.builder()
                .productId(kp.getProductId())
                .status(kp.getStatus())
                .productName(kp.getName())
                .price(nullToEmpty(llmDetails.get("price")).isBlank() ? kp.getPrice() : llmDetails.get("price"))
                .category(nullToEmpty(llmDetails.get("category")).isBlank() ? kp.getCategory() : llmDetails.get("category"))
                .brand(kp.getBrand())
                .description(description)
                .productUrl(kp.getProductUrl())
                .taskLink(kp.getTaskLink())
                .whatAreYouLookingFor(nullToEmpty(llmDetails.get("whatAreYouLookingFor")))
                .ingredients(nullToEmpty(llmDetails.get("ingredients")))
                .sourceFileName("LLM-generated")
                .build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static <T> java.util.function.Predicate<T> distinctByKey(java.util.function.Function<? super T, ?> keyExtractor) {
        Set<Object> seen = new HashSet<>();
        return t -> seen.add(keyExtractor.apply(t));
    }

    /** Pattern to strip volume/size (e.g. "100 ML", "50ML", "1.7 OZ", "3.4 FL OZ"). */
    private static final Pattern VOLUME_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:ml|oz|fl\\.?\\s*oz)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Brand prefixes to remove from the start of product names (longer first). */
    private static final List<String> BRAND_PREFIXES = List.of("tom ford ", "tom ford", "tomford");

    /**
     * Normalizes product name for comparison the same way as manual analysis:
     * remove volume indicators (e.g. "100 ML", "50 ML"), remove brand prefixes (e.g. "Tom Ford"),
     * then trim, lowercase, and collapse spaces. Enables matching when the two CSVs format titles differently.
     */
    private String normalizeProductNameForComparison(String name) {
        if (name == null || name.isBlank()) return null;
        String s = name.trim().toLowerCase(Locale.ROOT);
        s = VOLUME_PATTERN.matcher(s).replaceAll("");
        for (String prefix : BRAND_PREFIXES) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).trim();
                break;
            }
        }
        s = s.replaceAll("^[-–—:\\s]+", "").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Returns true if the normalized Tomford core name matches any Kingpower core name.
     * Match is exact or when one core name contains the other.
     */
    private boolean matchesAnyKingpowerProduct(String tomfordCoreName, Set<String> kingpowerCoreNames) {
        if (tomfordCoreName == null || tomfordCoreName.isBlank() || kingpowerCoreNames == null || kingpowerCoreNames.isEmpty()) {
            return false;
        }
        for (String kpName : kingpowerCoreNames) {
            if (kpName == null || kpName.isBlank()) continue;
            if (tomfordCoreName.equals(kpName)) return true;
            if (tomfordCoreName.contains(kpName) || kpName.contains(tomfordCoreName)) return true;
        }
        return false;
    }

    /**
     * Called by the file listener when processing a single CSV (legacy path).
     * For each product: applies LLM to restructure the description, then saves to kingpower_product_extractions.
     */
    @Transactional
    public void handleParsedData(Path filePath, List<ProductDataDto> products) {
        if (products == null) {
            products = Collections.emptyList();
        }
        lastParsedProducts.clear();
        lastProcessedFileName = filePath.getFileName() != null ? filePath.getFileName().toString() : null;
        log.info("Processing {} product records from {} (LLM restructure for product_details, then DB save)", products.size(), lastProcessedFileName);

        for (ProductDataDto dto : products) {
            String restructured = geminiProductService.restructureProductDetails(
                    dto.getDescription(),
                    dto.getName(),
                    dto.getBrand()
            );
            dto.setRestructuredProductDetails(restructured);
            lastParsedProducts.add(dto);
        }

        List<KingpowerProductExtraction> entities = lastParsedProducts.stream()
                .map(this::toKingpowerProductExtraction)
                .collect(Collectors.toList());
        kingpowerRepository.saveAll(entities);
        log.info("Saved {} rows to kingpower_product_extractions (product_details = LLM-restructured)", entities.size());
    }

    /**
     * Maps DTO to entity. All CSV fields are persisted as-is except product_details,
     * which is set to the LLM-restructured value (or original description if restructure was skipped).
     */
    private KingpowerProductExtraction toKingpowerProductExtraction(ProductDataDto dto) {
        Integer sku = null;
        if (dto.getSku() != null && !dto.getSku().isBlank()) {
            try {
                sku = Integer.parseInt(dto.getSku().trim());
            } catch (NumberFormatException ignored) {
                // leave null if not a valid integer
            }
        }
        String productDetailsToSave = dto.getRestructuredProductDetails() != null && !dto.getRestructuredProductDetails().isBlank()
                ? dto.getRestructuredProductDetails()
                : dto.getDescription();
        return KingpowerProductExtraction.builder()
                .extractDate(LocalDateTime.now())
                .status(dto.getStatus())
                .originUrl(dto.getProductUrl())
                .brand(dto.getBrand())
                .productName(dto.getName())
                .productDetails(productDetailsToSave)
                .price(dto.getPrice())
                .sku(sku)
                .category(dto.getCategory())
                .build();
    }

    private BrandsiteProductExtraction toBrandsiteProductExtraction(TomfordProductDto dto) {
        String productDetailsToSave = dto.getRestructuredProductDetails() != null && !dto.getRestructuredProductDetails().isBlank()
                ? dto.getRestructuredProductDetails()
                : dto.getDescription();
        return BrandsiteProductExtraction.builder()
                .extractDate(LocalDateTime.now())
                .status(dto.getStatus())
                .brand(dto.getBrand())
                .productName(dto.getProductName())
                .productDetails(productDetailsToSave)
                .price(dto.getPrice())
                .category(dto.getCategory())
                .build();
    }

    /**
     * Returns the most recently parsed list of products (with restructuredProductDetails set).
     */
    public List<ProductDataDto> getLastParsedProducts() {
        return Collections.unmodifiableList(lastParsedProducts);
    }

    /**
     * Returns the file name of the last processed CSV, or null if none yet.
     */
    public String getLastProcessedFileName() {
        return lastProcessedFileName;
    }
}
