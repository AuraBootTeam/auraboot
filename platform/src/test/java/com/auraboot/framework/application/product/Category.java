package com.auraboot.framework.application.product;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
class Category {
    private String id;
    private String name;
    private Map<String, String> attributes;
    private List<Category> subcategories;
    private List<Product> products;
    
    // Getters and Setters
}
@Data

class Product {
    private String id;
    private String name;
    private Brand brand;
    private Price price;
    private Marketing marketing;
    private Benefits benefits;
    private Inventory inventory;
    private Warehouse warehouse;
    
    // Getters and Setters
}
@Data

class Brand {
    private String id;
    private String name;
    
    // Getters and Setters
}
@Data

class Price {
    private double originalPrice;
    private double finalPrice;
    private String currency;
    
    // Getters and Setters
}
@Data

class Marketing {
    private List<Coupon> coupons;
    private List<ActiveCampaign> activeCampaigns;
    
    // Getters and Setters
}
@Data

class Coupon {
    private String id;
    private String type;
    private double value;
    private String validity;
    
    // Getters and Setters
}
@Data

class ActiveCampaign {
    private String id;
    private String description;
    private String startDate;
    private String endDate;
    
    // Getters and Setters
}
@Data

class Benefits {
    private List<String> platformBenefits;
    private List<String> shopBenefits;
    
    // Getters and Setters
}
@Data

class Inventory {
    private String productId;
    private String skuId;
    private Map<String, Integer> stockByChannel;
    private int totalStockQuantity;
    private int soldStock;
    private int availableStock;
    private String stockStatus;
    
    // Getters and Setters
}
@Data

class Warehouse {
    private Map<String, StockDetails> locations;
    
    // Getters and Setters
}
@Data
class StockDetails {
    private int physicalStock;
    private int shippedStock;
    
    // Getters and Setters
}
