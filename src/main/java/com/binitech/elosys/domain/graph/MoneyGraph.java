package com.binitech.elosys.domain.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MoneyGraph {
  public static final byte DONATION = 1;
  public static final byte PAYMENT = 2;
  public static final byte BOTH = 3;

  private final Map<String, Integer> nodeIds = new HashMap<>();
  private final List<String> nodes = new ArrayList<>();
  private final LongIntMap edgeIndex = new LongIntMap(1 << 20);
  private long[] edgeKeys = new long[1 << 20];
  private byte[] edgeKinds = new byte[1 << 20];
  private long[] edgeAmounts = new long[1 << 20];
  private int edgeCount;

  private int[] offsets;
  private int[] targets;

  public void addEdge(String src, String dst, byte kind, Long amountCents) {
    if (src.equals(dst)) return;
    int s = nodeId(src);
    int d = nodeId(dst);
    long key = key(s, d);
    long amount = amountCents == null ? 0 : amountCents;
    int idx = edgeIndex.get(key);
    if (idx >= 0) {
      if (edgeKinds[idx] != kind) edgeKinds[idx] = BOTH;
      edgeAmounts[idx] += amount;
      return;
    }
    if (edgeCount == edgeKeys.length) {
      int cap = edgeKeys.length * 2;
      edgeKeys = Arrays.copyOf(edgeKeys, cap);
      edgeKinds = Arrays.copyOf(edgeKinds, cap);
      edgeAmounts = Arrays.copyOf(edgeAmounts, cap);
    }
    edgeKeys[edgeCount] = key;
    edgeKinds[edgeCount] = kind;
    edgeAmounts[edgeCount] = amount;
    edgeIndex.put(key, edgeCount);
    edgeCount++;
    offsets = null;
  }

  private int nodeId(String key) {
    Integer id = nodeIds.get(key);
    if (id != null) return id;
    int created = nodes.size();
    nodeIds.put(key, created);
    nodes.add(key);
    return created;
  }

  private static long key(int s, int d) {
    return ((long) s << 32) | (d & 0xffffffffL);
  }

  public int nodeCount() {
    return nodes.size();
  }

  public int edgeCount() {
    return edgeCount;
  }

  public String node(int id) {
    return nodes.get(id);
  }

  public byte kind(int s, int d) {
    int idx = edgeIndex.get(key(s, d));
    return idx < 0 ? 0 : edgeKinds[idx];
  }

  public long amount(int s, int d) {
    int idx = edgeIndex.get(key(s, d));
    return idx < 0 ? 0 : edgeAmounts[idx];
  }

  public int[] neighbors(int v) {
    return Arrays.copyOfRange(targets, offsets[v], offsets[v + 1]);
  }

  int outDegree(int v) {
    return offsets[v + 1] - offsets[v];
  }

  int neighbor(int v, int i) {
    return targets[offsets[v] + i];
  }

  public void freeze() {
    int n = nodes.size();
    offsets = new int[n + 1];
    for (int e = 0; e < edgeCount; e++) offsets[(int) (edgeKeys[e] >>> 32) + 1]++;
    for (int v = 0; v < n; v++) offsets[v + 1] += offsets[v];
    targets = new int[edgeCount];
    int[] fill = Arrays.copyOf(offsets, n);
    for (int e = 0; e < edgeCount; e++) {
      int s = (int) (edgeKeys[e] >>> 32);
      targets[fill[s]++] = (int) edgeKeys[e];
    }
  }

  boolean frozen() {
    return offsets != null;
  }

  static final class LongIntMap {
    private long[] keys;
    private int[] values;
    private boolean[] used;
    private int size;

    LongIntMap(int capacity) {
      int cap = Integer.highestOneBit(Math.max(16, capacity) - 1) << 1;
      keys = new long[cap];
      values = new int[cap];
      used = new boolean[cap];
    }

    int get(long key) {
      int mask = keys.length - 1;
      for (int i = mix(key) & mask; used[i]; i = (i + 1) & mask)
        if (keys[i] == key) return values[i];
      return -1;
    }

    void put(long key, int value) {
      if ((size + 1) * 2 > keys.length) grow();
      int mask = keys.length - 1;
      int i = mix(key) & mask;
      while (used[i]) {
        if (keys[i] == key) {
          values[i] = value;
          return;
        }
        i = (i + 1) & mask;
      }
      used[i] = true;
      keys[i] = key;
      values[i] = value;
      size++;
    }

    private void grow() {
      long[] oldKeys = keys;
      int[] oldValues = values;
      boolean[] oldUsed = used;
      keys = new long[oldKeys.length * 2];
      values = new int[oldKeys.length * 2];
      used = new boolean[oldKeys.length * 2];
      size = 0;
      for (int i = 0; i < oldKeys.length; i++) if (oldUsed[i]) put(oldKeys[i], oldValues[i]);
    }

    private static int mix(long key) {
      long h = key * 0x9E3779B97F4A7C15L;
      return (int) (h ^ (h >>> 32));
    }
  }
}
