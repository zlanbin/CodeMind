package com.demo;
public class InventoryClient {
    private int stock = 0;
    public void rollback(int id) {
        // restore stock
        stock += 1;
    }
}
