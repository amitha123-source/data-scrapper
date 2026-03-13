package com.sample.data_scrapper.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "automation_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String runId;

    @Column(nullable = false)
    private String status;

    private String robot1TaskId;
    private String robot2BulkRunId;
    private String robot3BulkRunId;

    private Integer productLinksCount;
    private Integer robot2RecordsCount;
    private Integer robot3RecordsCount;

    @Column(columnDefinition = "TEXT")
    private String categoryUrl;

    private Instant startedAt;
    private Instant completedAt;

    @PrePersist
    public void prePersist() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }
}
