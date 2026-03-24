package com.sample.data_scrapper.listener;

import com.sample.data_scrapper.config.ProductDataProperties;
import com.sample.data_scrapper.service.ProductDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Listens for CSV files in the configured product-data directory.
 * When Kingpower_data.csv and Tomford_data(2).csv are both present, runs the two-file workflow:
 * parse both, filter Tomford by Kingpower product names, LLM restructure, save to respective tables.
 */
@Slf4j
@Component
public class ProductDataFileListener implements ApplicationRunner {

    private final ProductDataProperties productDataProperties;
    private final ProductDataService productDataService;

    @Autowired
    public ProductDataFileListener(
            ProductDataProperties productDataProperties,
            @Lazy ProductDataService productDataService) {
        this.productDataProperties = productDataProperties;
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
     * Scan the directory and run the Kingpower + Tomford two-file workflow when both
     * Kingpower_data.csv and Tomford_data(2).csv are present.
     */
    private void processExistingCsvFiles(Path watchDir) {
        try {
            productDataService.runKingpowerAndTomfordWorkflow();
        } catch (Exception e) {
            log.warn("Initial two-file workflow did not run or failed: {}", e.getMessage());
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

                    // On any CSV create/modify, run the two-file workflow (Kingpower + Tomford)
                    runTwoFileWorkflow();
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

    private void runTwoFileWorkflow() {
        try {
            productDataService.runKingpowerAndTomfordWorkflow();
        } catch (Exception e) {
            log.error("Two-file workflow failed", e);
        }
    }
}
