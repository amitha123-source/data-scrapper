package com.sample.data_scrapper.service;

import com.sample.data_scrapper.dto.ProductDataDto;
import com.sample.data_scrapper.entity.KingpowerProductExtraction;
import com.sample.data_scrapper.repository.KingpowerProductExtractionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Processes CSV-derived product data: applies LLM restructure to product details,
 * then persists all data (with transformed product_details) to kingpower_product_extractions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductDataService {

    // private static final int MAX_LEN_45 = 45;
    // private static final int MAX_LEN_255 = 255;
    // private static final int MAX_LEN_500 = 500;

    private final GeminiProductService geminiProductService;
    private final KingpowerProductExtractionRepository kingpowerRepository;
    private final List<ProductDataDto> lastParsedProducts = new CopyOnWriteArrayList<>();
    private volatile String lastProcessedFileName;

    /**
     * Called by the file listener after CSV is parsed and mapped to DTOs.
     * For each product: applies LLM to restructure the description into product features,
     * then saves all fields to the DB with the restructured text in product_details.
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
                .taskLink(dto.getTaskLink())
                .originUrl(dto.getProductUrl())
                .brand(dto.getBrand())
                .productName(dto.getName())
                .productDetails(productDetailsToSave)
                .price(dto.getPrice())
                .sku(sku)
                .category(dto.getCategory())
                .build();
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) return null;
        String s = value.trim();
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
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
