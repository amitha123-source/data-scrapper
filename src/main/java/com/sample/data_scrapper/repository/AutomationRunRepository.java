package com.sample.data_scrapper.repository;

import com.sample.data_scrapper.entity.AutomationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AutomationRunRepository extends JpaRepository<AutomationRun, Long> {

    Optional<AutomationRun> findByRunId(String runId);
}
