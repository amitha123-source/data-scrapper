package com.sample.data_scrapper.repository;

import com.sample.data_scrapper.entity.BrandsiteProductExtraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BrandsiteProductExtractionRepository extends JpaRepository<BrandsiteProductExtraction, Long> {

    /** Removes existing rows for a brand before re-import (avoids duplicate rows on repeated workflow runs). */
    void deleteByBrand(String brand);
}
