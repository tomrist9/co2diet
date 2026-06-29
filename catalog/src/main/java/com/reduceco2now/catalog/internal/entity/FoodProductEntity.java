package com.reduceco2now.catalog.internal.entity;

import com.reduceco2now.catalog.Food;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;


@Entity
@Table(name = "food_product")
public class FoodProductEntity {

    @Id
    private Long id;

    private String barcode;
    private String name;
    private String brand;

    @Column(name = "category_code")
    private String categoryCode;

    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected FoodProductEntity() {

    }

    public Food toDomain() {

        return new Food(id, barcode, name, brand, categoryCode, rowVersion);
    }
}