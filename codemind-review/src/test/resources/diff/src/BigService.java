package com.demo;
public class BigService {
    public int compute(int a, int b, int c) {
        int r = 0;
        r += a * 31 + b;
        r += b * 17 - c;
        r += c * 13 + a;
        r = r ^ (r << 3);
        r = r + (a | b | c);
        r = r - (a & b & c);
        r = r * 7 + 11;
        r = r % 1000003;
        if (r < 0) r = -r;
        for (int i = 0; i < 4; i++) r += i * a;
        return r;
    }
}
