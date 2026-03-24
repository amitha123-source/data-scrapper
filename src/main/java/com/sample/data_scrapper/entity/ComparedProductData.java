package com.sample.data_scrapper.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "compared_product_data")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComparedProductData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "brand")
    private String brand;

    @Column(name = "product_details_from_king_power", columnDefinition = "TEXT")
    private String productDetailsFromKingPower;

    @Column(name = "product_details_from_brand_site", columnDefinition = "TEXT")
    private String productDetailsFromBrandSite;

    @Column(name = "category")
    private String category;

    /** One of: MATCH, MISMATCH, MISSING_IN_KINGPOWER, MISSING_IN_BRANDSITE */
    @Column(name = "status", length = 32)
    private String status;
}
