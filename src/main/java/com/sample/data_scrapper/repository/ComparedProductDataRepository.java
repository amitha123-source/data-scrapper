package com.sample.data_scrapper.repository;

import com.sample.data_scrapper.entity.ComparedProductData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ComparedProductDataRepository extends JpaRepository<ComparedProductData, Long> {
}
