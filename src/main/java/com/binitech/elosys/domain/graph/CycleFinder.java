package com.binitech.elosys.domain.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

public final class CycleFinder {
  private CycleFinder() {}

  public record Result(List<int[]> cycles, int nontrivialSccs, int totalSccs, long hubSkipped) {}

  public static Result find(MoneyGraph graph, int maxDepth, int maxFanout) {
    if (!graph.frozen()) graph.freeze();
    List<int[]> sccs = tarjan(graph);
    List<int[]> cycles = new ArrayList<>();
    long hubSkipped = 0;
    int nontrivial = 0;
    for (int[] scc : sccs) {
      if (scc.length < 2) continue;
      nontrivial++;
      hubSkipped += cyclesInScc(graph, scc, maxDepth, maxFanout, cycles);
    }
    return new Result(cycles, nontrivial, sccs.size(), hubSkipped);
  }

  static List<int[]> tarjan(MoneyGraph g) {
    int n = g.nodeCount();
    int[] index = new int[n];
    Arrays.fill(index, -1);
    int[] low = new int[n];
    boolean[] onStack = new boolean[n];
    int[] stack = new int[n];
    int sp = 0;
    int[] workNode = new int[n];
    int[] workPos = new int[n];
    List<int[]> sccs = new ArrayList<>();
    int counter = 0;

    for (int start = 0; start < n; start++) {
      if (index[start] != -1) continue;
      int wp = 0;
      workNode[wp] = start;
      workPos[wp] = 0;
      wp++;
      index[start] = low[start] = counter++;
      stack[sp++] = start;
      onStack[start] = true;
      while (wp > 0) {
        int v = workNode[wp - 1];
        int i = workPos[wp - 1];
        if (i < g.outDegree(v)) {
          int w = g.neighbor(v, i);
          workPos[wp - 1] = i + 1;
          if (index[w] == -1) {
            index[w] = low[w] = counter++;
            stack[sp++] = w;
            onStack[w] = true;
            workNode[wp] = w;
            workPos[wp] = 0;
            wp++;
          } else if (onStack[w]) {
            low[v] = Math.min(low[v], index[w]);
          }
        } else {
          wp--;
          if (wp > 0) {
            int parent = workNode[wp - 1];
            low[parent] = Math.min(low[parent], low[v]);
          }
          if (low[v] == index[v]) {
            int size = 0;
            int[] buf = new int[8];
            int w;
            do {
              w = stack[--sp];
              onStack[w] = false;
              if (size == buf.length) buf = Arrays.copyOf(buf, size * 2);
              buf[size++] = w;
            } while (w != v);
            sccs.add(Arrays.copyOf(buf, size));
          }
        }
      }
    }
    return sccs;
  }

  private static long cyclesInScc(
      MoneyGraph g, int[] scc, int maxDepth, int maxFanout, List<int[]> out) {
    BitSet members = new BitSet();
    for (int v : scc) members.set(v);
    java.util.Map<Integer, int[]> induced = new java.util.HashMap<>(scc.length * 2);
    for (int v : scc) {
      int deg = g.outDegree(v);
      int[] buf = new int[deg];
      int k = 0;
      for (int i = 0; i < deg; i++) {
        int w = g.neighbor(v, i);
        if (members.get(w)) buf[k++] = w;
      }
      induced.put(v, Arrays.copyOf(buf, k));
    }
    long[] hubSkipped = {0};
    int[] path = new int[maxDepth + 1];
    BitSet visited = new BitSet();
    for (int start : scc) {
      path[0] = start;
      visited.set(start);
      dfs(induced, start, start, path, 1, visited, maxDepth, maxFanout, out, hubSkipped);
      visited.clear(start);
    }
    return hubSkipped[0];
  }

  private static void dfs(
      java.util.Map<Integer, int[]> induced,
      int start,
      int current,
      int[] path,
      int depth,
      BitSet visited,
      int maxDepth,
      int maxFanout,
      List<int[]> out,
      long[] hubSkipped) {
    int[] neighbors = induced.getOrDefault(current, new int[0]);
    if (neighbors.length > maxFanout && current != start) {
      hubSkipped[0]++;
      return;
    }
    for (int next : neighbors) {
      if (next == start && depth >= 2) {
        out.add(Arrays.copyOf(path, depth));
      } else if (depth < maxDepth && next > start && !visited.get(next)) {
        visited.set(next);
        path[depth] = next;
        dfs(induced, start, next, path, depth + 1, visited, maxDepth, maxFanout, out, hubSkipped);
        visited.clear(next);
      }
    }
  }
}
