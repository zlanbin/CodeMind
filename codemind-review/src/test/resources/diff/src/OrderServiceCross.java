package com.demo;
public class OrderService {
    private final InventoryClient inventory = new InventoryClient();
    public void cancel(int id) {
        inventory.rollback(id);
    }
}
