package com.reduceco2now.catalog;


public record Food(
        long id,
        String barcode,
        String name,
        String brand,
        String categoryCode,
        long rowVersion
) {}