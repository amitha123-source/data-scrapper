package com.sample.data_scrapper.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "kingpower_product_extractions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KingpowerProductExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "extract_date")
    private LocalDateTime extractDate;

    @Column(name = "status")
    private String status;

    @Column(name = "origin_url")
    private String originUrl;

    @Column(name = "brand")
    private String brand;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "product_details")
    private String productDetails;

    @Column(name ="price")
    private String price;

    @Column(name = "SKU")
    private Integer sku;

    @Column(name = "category")
    private String category;
}
