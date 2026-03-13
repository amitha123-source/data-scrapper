package com.sample.data_scrapper.repository;

import com.sample.data_scrapper.entity.KingpowerProductExtraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KingpowerProductExtractionRepository extends JpaRepository<KingpowerProductExtraction, Long> {
}
