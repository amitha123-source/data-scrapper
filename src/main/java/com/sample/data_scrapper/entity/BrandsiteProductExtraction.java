package com.sample.data_scrapper.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "brandsite_product_extractions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandsiteProductExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "extract_date")
    private LocalDateTime extractDate;

    @Column(length = 45)
    private String status;

    @Column(name = "task_link", length = 255)
    private String taskLink;

    @Column(name = "origin_url", length = 255)
    private String originUrl;

    @Column(name = "what_are_you_looking_for", length = 45)
    private String whatAreYouLookingFor;

    @Column(length = 45)
    private String brand;

    @Column(name = "product_name", length = 45)
    private String productName;

    @Column(name = "product_details", length = 500)
    private String productDetails;

    @Column(length = 45)
    private String price;

    @Column(length = 45)
    private String category;

    @Column(name = "Ingredients", length = 500)
    private String ingredients;
}
