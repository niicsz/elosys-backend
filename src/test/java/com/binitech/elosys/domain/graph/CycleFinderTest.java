package com.binitech.elosys.domain.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CycleFinderTest {

  private static List<String> named(MoneyGraph g, int[] cycle) {
    return Arrays.stream(cycle).mapToObj(g::node).toList();
  }

  @Test
  void findsEachSimpleCycleOnceStartingAtItsSmallestNode() {
    MoneyGraph g = new MoneyGraph();
    g.addEdge("A", "B", MoneyGraph.DONATION, 100L);
    g.addEdge("B", "C", MoneyGraph.PAYMENT, 200L);
    g.addEdge("C", "A", MoneyGraph.DONATION, 300L);
    g.addEdge("C", "D", MoneyGraph.PAYMENT, 50L);

    CycleFinder.Result r = CycleFinder.find(g, 5, 400);

    assertThat(r.cycles()).hasSize(1);
    assertThat(named(g, r.cycles().getFirst())).containsExactly("A", "B", "C");
    assertThat(r.nontrivialSccs()).isEqualTo(1);
  }

  @Test
  void aggregatesParallelEdgesAndMarksMixedKinds() {
    MoneyGraph g = new MoneyGraph();
    g.addEdge("A", "B", MoneyGraph.DONATION, 100L);
    g.addEdge("A", "B", MoneyGraph.PAYMENT, 50L);
    g.addEdge("B", "A", MoneyGraph.DONATION, null);
    g.addEdge("A", "A", MoneyGraph.DONATION, 999L);
    g.freeze();

    assertThat(g.edgeCount()).isEqualTo(2);
    assertThat(g.amount(0, 1)).isEqualTo(150);
    assertThat(g.kind(0, 1)).isEqualTo(MoneyGraph.BOTH);
    assertThat(CycleFinder.find(g, 5, 400).cycles()).hasSize(1);
  }

  @Test
  void respectsMaxDepth() {
    MoneyGraph g = new MoneyGraph();
    String[] ring = {"A", "B", "C", "D", "E", "F"};
    for (int i = 0; i < ring.length; i++)
      g.addEdge(ring[i], ring[(i + 1) % ring.length], MoneyGraph.DONATION, 1L);

    assertThat(CycleFinder.find(g, 5, 400).cycles()).isEmpty();
    assertThat(CycleFinder.find(g, 6, 400).cycles()).hasSize(1);
  }

  @Test
  void skipsBranchingThroughHubs() {
    MoneyGraph g = new MoneyGraph();
    g.addEdge("A", "HUB", MoneyGraph.DONATION, 1L);
    g.addEdge("HUB", "A", MoneyGraph.DONATION, 1L);
    g.addEdge("HUB", "B", MoneyGraph.DONATION, 1L);
    g.addEdge("B", "HUB", MoneyGraph.DONATION, 1L);

    CycleFinder.Result r = CycleFinder.find(g, 5, 1);

    assertThat(r.hubSkipped()).isPositive();
  }
}
