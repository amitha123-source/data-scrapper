package com.sample.data_scrapper.service;

import com.sample.data_scrapper.entity.BrandsiteProductExtraction;
import com.sample.data_scrapper.entity.ComparedProductData;
import com.sample.data_scrapper.entity.ComparisonStatus;
import com.sample.data_scrapper.entity.KingpowerProductExtraction;
import com.sample.data_scrapper.repository.BrandsiteProductExtractionRepository;
import com.sample.data_scrapper.repository.ComparedProductDataRepository;
import com.sample.data_scrapper.repository.KingpowerProductExtractionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compares product data from Kingpower and Brandsite extractions and persists
 * comparison results to compared_product_data (status: MATCH, MISMATCH, MISSING_IN_KINGPOWER, MISSING_IN_BRANDSITE).
 * Uses normalized product names (trim, case-insensitive, strip volume/units and fragrance suffixes) and fuzzy
 * matching (e.g. Levenshtein similarity) so variants like "OUD WOOD 100 ML" and "OUD WOOD EAU DE PARFUM" match.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductComparisonService {

    private static final Pattern VOLUME_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:ml|oz|fl\\.?\\s*oz)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> BRAND_PREFIXES = List.of("tom ford ", "tom ford", "tomford");
    /** Suffixes to strip from product names for matching (longer first). Case-insensitive, trimmed from end. */
    private static final List<String> PRODUCT_SUFFIXES = List.of(
            "eau de parfum spray", "eau de toilette spray", "eau de cologne spray",
            "eau de parfum", "eau de toilette", "eau de cologne",
            "edp", "edt", "edc",
            "parfum", "spray", "for men", "for women");

    /** Minimum similarity ratio (0–1) to consider two core names the same product. */
    private static final double FUZZY_MATCH_THRESHOLD = 0.85;

    private final KingpowerProductExtractionRepository kingpowerRepository;
    private final BrandsiteProductExtractionRepository brandsiteRepository;
    private final ComparedProductDataRepository comparedProductDataRepository;

    /**
     * Fetches all records from both extraction tables, matches products by brand + normalized core product name
     * (with fuzzy matching for near-identical names), compares product details, and appends one row per logical
     * product into compared_product_data. Each run produces unique consolidated results per product (no duplicate
     * rows for the same product within the run).
     */
    @Transactional
    public int runComparisonWorkflow() {
        List<KingpowerProductExtraction> kingpowerList = kingpowerRepository.findAll();
        List<BrandsiteProductExtraction> brandsiteList = brandsiteRepository.findAll();

        if (kingpowerList.isEmpty() && brandsiteList.isEmpty()) {
            log.warn("No data in kingpower or brandsite tables; skipping comparison.");
            return 0;
        }

        Map<String, List<KingpowerProductExtraction>> kpByKey = kingpowerList.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(k -> comparisonKey(k.getBrand(), k.getProductName()), LinkedHashMap::new, Collectors.toList()));
        Map<String, List<BrandsiteProductExtraction>> bsByKey = brandsiteList.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(b -> comparisonKey(b.getBrand(), b.getProductName()), LinkedHashMap::new, Collectors.toList()));

        Set<String> exactKeys = new HashSet<>(kpByKey.keySet());
        exactKeys.retainAll(bsByKey.keySet());

        Set<String> kpOnlyKeys = new HashSet<>(kpByKey.keySet());
        kpOnlyKeys.removeAll(bsByKey.keySet());
        Set<String> bsOnlyKeys = new HashSet<>(bsByKey.keySet());
        bsOnlyKeys.removeAll(kpByKey.keySet());

        List<ComparedProductData> toSave = new ArrayList<>();

        for (String key : exactKeys) {
            KingpowerProductExtraction kp = kpByKey.get(key).get(0);
            BrandsiteProductExtraction bs = bsByKey.get(key).get(0);
            toSave.add(buildComparisonRow(kp, bs));
        }

        Map<String, String> fuzzyPairs = new HashMap<>();
        for (String kpKey : new ArrayList<>(kpOnlyKeys)) {
            String kpCore = coreNameFromKey(kpKey);
            if (kpCore == null || kpCore.isBlank()) continue;
            String bestBsKey = null;
            double bestScore = FUZZY_MATCH_THRESHOLD;
            for (String bsKey : bsOnlyKeys) {
                String bsCore = coreNameFromKey(bsKey);
                if (bsCore == null || bsCore.isBlank()) continue;
                double score = similarityScore(kpCore, bsCore);
                if (score >= bestScore) {
                    bestScore = score;
                    bestBsKey = bsKey;
                }
            }
            if (bestBsKey != null) {
                fuzzyPairs.put(kpKey, bestBsKey);
                kpOnlyKeys.remove(kpKey);
                bsOnlyKeys.remove(bestBsKey);
            }
        }

        for (Map.Entry<String, String> e : fuzzyPairs.entrySet()) {
            KingpowerProductExtraction kp = kpByKey.get(e.getKey()).get(0);
            BrandsiteProductExtraction bs = bsByKey.get(e.getValue()).get(0);
            toSave.add(buildComparisonRow(kp, bs));
        }

        for (String key : kpOnlyKeys) {
            KingpowerProductExtraction kp = kpByKey.get(key).get(0);
            toSave.add(buildComparisonRow(kp, null));
        }
        for (String key : bsOnlyKeys) {
            BrandsiteProductExtraction bs = bsByKey.get(key).get(0);
            toSave.add(buildComparisonRow(null, bs));
        }

        if (!toSave.isEmpty()) {
            comparedProductDataRepository.saveAll(toSave);
        }
        log.info("Appended {} unique comparison rows to compared_product_data (MATCH/MISMATCH/MISSING_*).", toSave.size());
        return toSave.size();
    }

    private String coreNameFromKey(String key) {
        if (key == null || !key.contains("|")) return "";
        return key.substring(key.indexOf('|') + 1).trim();
    }

    private double similarityScore(String a, String b) {
        if (a == null || b == null) return 0;
        a = a.trim();
        b = b.trim();
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1.0;
        if (a.contains(b) || b.contains(a)) return 0.9;
        return levenshteinRatio(a, b);
    }

    private static double levenshteinRatio(String s1, String s2) {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0;
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLen;
    }

    private static int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = curr;
            curr = t;
        }
        return prev[n];
    }

    private ComparedProductData buildComparisonRow(KingpowerProductExtraction kp, BrandsiteProductExtraction bs) {
        String productName;
        String brand;
        String category;
        String detailsKp;
        String detailsBs;
        ComparisonStatus status;

        if (kp != null && bs != null) {
            productName = kp.getProductName();
            brand = kp.getBrand();
            category = kp.getCategory();
            detailsKp = nullToEmpty(kp.getProductDetails());
            detailsBs = nullToEmpty(bs.getProductDetails());
            boolean detailsMatch = normalizeForComparison(detailsKp).equals(normalizeForComparison(detailsBs));
            status = detailsMatch ? ComparisonStatus.MATCH : ComparisonStatus.MISMATCH;
        } else if (kp != null) {
            productName = kp.getProductName();
            brand = kp.getBrand();
            category = kp.getCategory();
            detailsKp = nullToEmpty(kp.getProductDetails());
            detailsBs = null;
            status = ComparisonStatus.MISSING_IN_BRANDSITE;
        } else if (bs != null) {
            productName = bs.getProductName();
            brand = bs.getBrand();
            category = bs.getCategory();
            detailsKp = null;
            detailsBs = nullToEmpty(bs.getProductDetails());
            status = ComparisonStatus.MISSING_IN_KINGPOWER;
        } else {
            return null;
        }

        return ComparedProductData.builder()
                .productName(productName)
                .brand(brand)
                .category(category)
                .productDetailsFromKingPower(detailsKp)
                .productDetailsFromBrandSite(detailsBs)
                .status(status.name())
                .build();
    }

    private String comparisonKey(String brand, String productName) {
        String b = brand != null ? brand.trim().toLowerCase(Locale.ROOT) : "";
        String norm = normalizeProductNameForComparison(productName);
        return b + "|" + (norm != null ? norm : "");
    }

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
        for (String suffix : PRODUCT_SUFFIXES) {
            String suf = suffix.trim().toLowerCase(Locale.ROOT);
            if (suf.isEmpty()) continue;
            s = s.replaceAll("\\s+" + Pattern.quote(suf) + "\\s*$", " ").trim();
            s = s.replaceAll("^\\s*" + Pattern.quote(suf) + "\\s+", " ").trim();
        }
        s = s.replaceAll("^[-–—:\\s]+", "").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    /** Normalize product details text for equality check (trim, collapse whitespace, lowercase). */
    private String normalizeForComparison(String text) {
        if (text == null || text.isBlank()) return "";
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
