package com.codemind.review.domain.chunk;

/** 并查集，用于按调用关系把 unit 归组。 */
final class DisjointSet {

    private final int[] parent;

    DisjointSet(int size) {
        parent = new int[size];
        for (int i = 0; i < size; i++) {
            parent[i] = i;
        }
    }

    int find(int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    void union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra != rb) {
            // 并到较小根，保持归组的首次出现顺序稳定
            if (ra < rb) {
                parent[rb] = ra;
            } else {
                parent[ra] = rb;
            }
        }
    }
}
