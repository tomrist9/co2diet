package com.reduceco2now.catalog.internal.service;

import com.reduceco2now.catalog.CatalogQuery;
import com.reduceco2now.catalog.Food;
import com.reduceco2now.catalog.internal.entity.FoodProductEntity;
import com.reduceco2now.catalog.internal.repository.FoodProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;


@Service
@Transactional(readOnly = true)
class CatalogService implements CatalogQuery {

    private final FoodProductRepository repo;

    CatalogService(FoodProductRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<Food> byId(long id) {

        return repo.findById(id).map(FoodProductEntity::toDomain);
    }

    @Override
    public Optional<Food> byBarcode(String barcode) {
        return repo.findByBarcode(barcode).map(FoodProductEntity::toDomain);
    }

    @Override
    public List<Food> search(String query, String categoryCode, int limit) {
        return repo.search(query, categoryCode).stream()
                .limit(Math.max(1, limit))
                .map(FoodProductEntity::toDomain)
                .toList();
    }
}