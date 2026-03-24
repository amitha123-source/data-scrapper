package com.sample.data_scrapper.service;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.sample.data_scrapper.dto.ProductDataDto;
import com.sample.data_scrapper.dto.TomfordProductDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses CSV files and maps each row to {@link ProductDataDto}.
 * Supports flexible header names (e.g. "Product ID", "product_id", "name", "Product Name").
 * Uses Jackson CSV (same as rest of the app) to avoid classpath issues.
 */
@Slf4j
@Service
public class ProductDataCsvService {

    private static final CsvMapper CSV_MAPPER = new CsvMapper();
    private static final CsvSchema SCHEMA = CsvSchema.emptySchema().withHeader();

    private static final String[] PRODUCT_ID_KEYS = { "product_id", "productid", "id", "sku_id" };
    private static final String[] STATUS_KEYS = { "status", "Status" };
    private static final String[] NAME_KEYS = { "name", "product_name", "productname", "title", "product_title" };
    private static final String[] PRICE_KEYS = { "price", "unit_price", "unitprice" };
    private static final String[] CATEGORY_KEYS = { "category", "product_category", "productcategory" };
    private static final String[] BRAND_KEYS = { "brand", "brand_name", "brandname" };
    private static final String[] DESCRIPTION_KEYS = { "product_details", "description", "product_description", "productdescription" };
    private static final String[] URL_KEYS = { "origin_url", "product_url", "producturl", "url", "link", "product_link" };
    private static final String[] TASK_LINK_KEYS = { "task_link", "tasklink", "taskLink" };
    private static final String[] SKU_KEYS = { "sku", "sku_id" };
    private static final String[] WHAT_ARE_YOU_LOOKING_FOR_KEYS = { "what_are_you_looking_for", "whatareyoulookingfor" };
    private static final String[] INGREDIENTS_KEYS = { "ingredients", "Ingredients" };

    /**
     * Parse a CSV file (with header row) and map each data row to {@link ProductDataDto}.
     *
     * @param csvPath path to the CSV file
     * @return list of DTOs (one per data row); empty list if file is empty or invalid
     */
    public List<ProductDataDto> parseCsvFile(Path csvPath) {
        String fileName = csvPath.getFileName() != null ? csvPath.getFileName().toString() : null;
        List<ProductDataDto> result = new ArrayList<>();

        try (var reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class)
                    .with(SCHEMA)
                    .readValues(reader);

            int rowNum = 0;
            while (it.hasNext()) {
                rowNum++;
                try {
                    Map<String, String> rawRow = it.next();
                    if (rawRow == null || rawRow.isEmpty()) continue;
                    // Trim values
                    Map<String, String> trimmed = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : rawRow.entrySet()) {
                        String k = e.getKey();
                        String v = e.getValue();
                        trimmed.put(k, v != null ? v.trim() : null);
                    }
                    ProductDataDto dto = mapRowToDto(trimmed, fileName);
                    result.add(dto);
                } catch (Exception e) {
                    log.warn("Skipping invalid row {} in {}: {}", rowNum, fileName, e.getMessage());
                }
            }
            log.info("Parsed {} rows from CSV file: {}", result.size(), fileName);
        } catch (IOException e) {
            log.error("Failed to read CSV file: {}", csvPath, e);
            throw new IllegalStateException("Failed to parse CSV: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * Parse a CSV file as Tomford (brand-site) data and map each row to {@link TomfordProductDto}.
     */
    public List<TomfordProductDto> parseTomfordCsv(Path csvPath) {
        String fileName = csvPath.getFileName() != null ? csvPath.getFileName().toString() : null;
        List<TomfordProductDto> result = new ArrayList<>();
        try (var reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class)
                    .with(SCHEMA)
                    .readValues(reader);
            int rowNum = 0;
            while (it.hasNext()) {
                rowNum++;
                try {
                    Map<String, String> rawRow = it.next();
                    if (rawRow == null || rawRow.isEmpty()) continue;
                    Map<String, String> trimmed = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : rawRow.entrySet()) {
                        String k = e.getKey();
                        String v = e.getValue();
                        trimmed.put(k, v != null ? v.trim() : null);
                    }
                    result.add(mapRowToTomfordDto(trimmed, fileName));
                } catch (Exception e) {
                    log.warn("Skipping invalid Tomford row {} in {}: {}", rowNum, fileName, e.getMessage());
                }
            }
            log.info("Parsed {} Tomford rows from CSV: {}", result.size(), fileName);
        } catch (IOException e) {
            log.error("Failed to read Tomford CSV: {}", csvPath, e);
            throw new IllegalStateException("Failed to parse Tomford CSV: " + e.getMessage(), e);
        }
        return result;
    }

    private TomfordProductDto mapRowToTomfordDto(Map<String, String> rawRow, String sourceFileName) {
        TomfordProductDto.TomfordProductDtoBuilder builder = TomfordProductDto.builder()
                .rawRow(new LinkedHashMap<>(rawRow))
                .sourceFileName(sourceFileName);
        String v = firstMatching(rawRow, PRODUCT_ID_KEYS);
        if (v != null) builder.productId(v);
        v = firstMatching(rawRow, STATUS_KEYS);
        if (v != null) builder.status(v);
        v = firstMatching(rawRow, NAME_KEYS);
        if (v != null) builder.productName(v);
        v = firstMatching(rawRow, PRICE_KEYS);
        if (v != null) builder.price(v);
        v = firstMatching(rawRow, CATEGORY_KEYS);
        if (v != null) builder.category(v);
        v = firstMatching(rawRow, BRAND_KEYS);
        if (v != null) builder.brand(v);
        v = firstMatching(rawRow, DESCRIPTION_KEYS);
        if (v != null) builder.description(v);
        v = firstMatching(rawRow, URL_KEYS);
        if (v != null) builder.productUrl(v);
        v = firstMatching(rawRow, TASK_LINK_KEYS);
        if (v != null) builder.taskLink(v);
        v = firstMatching(rawRow, WHAT_ARE_YOU_LOOKING_FOR_KEYS);
        if (v != null) builder.whatAreYouLookingFor(v);
        v = firstMatching(rawRow, INGREDIENTS_KEYS);
        if (v != null) builder.ingredients(v);
        return builder.build();
    }

    private ProductDataDto mapRowToDto(Map<String, String> rawRow, String sourceFileName) {
        ProductDataDto.ProductDataDtoBuilder builder = ProductDataDto.builder()
                .rawRow(new LinkedHashMap<>(rawRow))
                .sourceFileName(sourceFileName);

        String v = firstMatching(rawRow, PRODUCT_ID_KEYS);
        if (v != null) builder.productId(v);
        v = firstMatching(rawRow, STATUS_KEYS);
        if (v != null) builder.status(v);
        v = firstMatching(rawRow, NAME_KEYS);
        if (v != null) builder.name(v);
        v = firstMatching(rawRow, PRICE_KEYS);
        if (v != null) builder.price(v);
        v = firstMatching(rawRow, CATEGORY_KEYS);
        if (v != null) builder.category(v);
        v = firstMatching(rawRow, BRAND_KEYS);
        if (v != null) builder.brand(v);
        v = firstMatching(rawRow, DESCRIPTION_KEYS);
        if (v != null) builder.description(v);
        v = firstMatching(rawRow, URL_KEYS);
        if (v != null) builder.productUrl(v);
        v = firstMatching(rawRow, TASK_LINK_KEYS);
        if (v != null) builder.taskLink(v);
        v = firstMatching(rawRow, SKU_KEYS);
        if (v != null) builder.sku(v);

        return builder.build();
    }

    private String firstMatching(Map<String, String> rawRow, String[] possibleKeys) {
        for (Map.Entry<String, String> entry : rawRow.entrySet()) {
            String normalizedHeader = normalizeHeader(entry.getKey());
            for (String key : possibleKeys) {
                if (normalizedHeader.equals(key) && entry.getValue() != null && !entry.getValue().isBlank()) {
                    return entry.getValue().trim();
                }
            }
        }
        return null;
    }

    private static String normalizeHeader(String header) {
        if (header == null) return "";
        return header.trim().toLowerCase().replaceAll("\\s+", "_");
    }
}
