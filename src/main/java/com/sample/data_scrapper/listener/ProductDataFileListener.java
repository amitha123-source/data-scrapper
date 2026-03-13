package com.sample.data_scrapper.listener;

import com.sample.data_scrapper.config.ProductDataProperties;
import com.sample.data_scrapper.dto.ProductDataDto;
import com.sample.data_scrapper.service.ProductDataCsvService;
import com.sample.data_scrapper.service.ProductDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Listens for CSV files in the configured product-data directory.
 * On create or modify, parses the CSV and maps rows to {@link ProductDataDto}, then hands off to {@link ProductDataService}.
 */
@Slf4j
@Component
public class ProductDataFileListener implements ApplicationRunner {

    private final ProductDataProperties productDataProperties;
    private final ProductDataCsvService csvService;
    private final ProductDataService productDataService;

    @Autowired
    public ProductDataFileListener(
            ProductDataProperties productDataProperties,
            @Lazy ProductDataCsvService csvService,
            @Lazy ProductDataService productDataService) {
        this.productDataProperties = productDataProperties;
        this.csvService = csvService;
        this.productDataService = productDataService;
    }

    private volatile boolean running = true;
    private ExecutorService watchExecutor;

    @Override
    public void run(ApplicationArguments args) {
        Path watchDir = Paths.get(productDataProperties.getWatchDirectory());
        if (!Files.isDirectory(watchDir)) {
            log.warn("Product data watch directory does not exist or is not a directory: {}. Listener will not start. Create the folder to enable.", watchDir);
            return;
        }

        // Process any CSV files already in the folder on startup
        processExistingCsvFiles(watchDir);

        // Start background watcher for new or modified CSV files
        watchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "product-data-csv-watcher");
            t.setDaemon(false);
            return t;
        });
        watchExecutor.submit(() -> watchDirectory(watchDir));

        log.info("Product data file listener started for directory: {}", watchDir);
    }

    /**
     * Scan the directory for existing CSV files and process them (most recent last so it wins in ProductDataService).
     */
    private void processExistingCsvFiles(Path watchDir) {
        try (Stream<Path> stream = Files.list(watchDir)) {
            List<Path> csvFiles = stream
                    .filter(p -> Files.isRegularFile(p))
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .toList();
            if (csvFiles.isEmpty()) {
                log.info("No existing CSV files found in {}", watchDir);
            } else {
                log.info("Processing {} existing CSV file(s) in {}", csvFiles.size(), watchDir);
                for (Path csvPath : csvFiles) {
                    processCsvFile(csvPath);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list directory for initial CSV scan: {}", watchDir, e);
        }
    }

    private void watchDirectory(Path watchDir) {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            watchDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

            while (running) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Product data file listener interrupted");
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path name = ev.context();
                    if (name == null) continue;

                    String fileName = name.toString().toLowerCase();
                    if (!fileName.endsWith(".csv")) {
                        continue;
                    }

                    Path resolved = watchDir.resolve(name);
                    if (!Files.isRegularFile(resolved)) {
                        continue;
                    }

                    processCsvFile(resolved);
                }

                boolean valid = key.reset();
                if (!valid) {
                    log.warn("Watch key no longer valid for {}", watchDir);
                    break;
                }
            }
        } catch (ClosedWatchServiceException e) {
            log.debug("WatchService closed");
        } catch (Exception e) {
            log.error("Error in product data file listener for {}", watchDir, e);
        }
    }

    private void processCsvFile(Path csvPath) {
        try {
            List<ProductDataDto> products = csvService.parseCsvFile(csvPath);
            productDataService.handleParsedData(csvPath, products);
        } catch (Exception e) {
            log.error("Failed to process CSV file: {}", csvPath, e);
        }
    }
}
