package com.reduceco2now.catalog;

import java.util.List;
import java.util.Optional;

/**
 *   GET /api/v1/foods/{id}
 *   GET /api/v1/foods/barcode/{code}
 *   GET /api/v1/foods/search?q=&category=
 */
public interface CatalogQuery {

    Optional<Food> byId(long id);

    Optional<Food> byBarcode(String barcode);

    List<Food> search(String query, String categoryCode, int limit);
}