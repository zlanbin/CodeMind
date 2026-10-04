package com.demo;
public class OrderService {
    private int stock = 10;
    public void cancel(int id) {
        stock = stock + 1;
        log(id);
    }
    private void log(int id) {
        System.out.println(id);
    }
}
