package com.sample.data_scrapper.entity;

/**
 * Status of product comparison between Kingpower and Brandsite extractions.
 */
public enum ComparisonStatus {
    /** Both sources have the product and details are equivalent. */
    MATCH,
    /** Both sources have the product but details differ. */
    MISMATCH,
    /** Product exists in Brandsite but not in Kingpower. */
    MISSING_IN_KINGPOWER,
    /** Product exists in Kingpower but not in Brandsite. */
    MISSING_IN_BRANDSITE
}
