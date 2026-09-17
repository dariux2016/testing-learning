package com.example.testinglearning.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    protected OrderItem() {
        // for JPA
    }

    public OrderItem(String productName, int quantity, BigDecimal unitPrice) {
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    void assignTo(Order order) {
        this.order = order;
    }

    public Long getId() {
        return id;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public Order getOrder() {
        return order;
    }
}
