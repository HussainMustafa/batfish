package org.batfish.bddreachability;

import static org.batfish.common.util.CommonUtil.toImmutableMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.sf.javabdd.BDD;
import org.batfish.common.bdd.BDDPacket;
import org.batfish.z3.IngressLocation;
import org.batfish.z3.expr.StateExpr;
import org.batfish.z3.state.OriginateInterfaceLink;
import org.batfish.z3.state.OriginateVrf;
import org.batfish.z3.state.Query;
import org.batfish.z3.state.visitors.DefaultTransitionGenerator;

/**
 * A new reachability analysis engine using BDDs. The analysis maintains a graph that describes how
 * packets flow through the network and through logical phases of a router. The graph is similar to
 * the one generated by {@link DefaultTransitionGenerator} for reachability analysis using NOD. In
 * particular, the graph nodes are {@link StateExpr StateExprs} and the edges are mostly the same as
 * the NOD program rules/transitions. {@link BDD BDDs} label the nodes and edges of the graph. A
 * node label represent the set of packets that can reach that node, and an edge label represents
 * the set of packets that can traverse the edge. There is a single designated {@link Query}
 * StateExpr that we compute reachability sets (i.e. sets of packets that reach the query state).
 * The query state never has any out-edges, and has in-edges from the dispositions of interest.
 *
 * <p>The two main departures from the NOD program are: 1) ACLs are encoded as a single BDD that
 * labels an edge (rather than a series of states/transitions in NOD programs). 2) Source NAT is
 * handled differently -- we don't maintain separate original and current source IP variables.
 * Instead, we keep track of where/how the packet is transformed as it flows through the network,
 * and reconstruct it after the fact. This requires some work that can't be expressed in BDDs.
 *
 * <p>We currently implement backward all-pairs reachability. Forward reachability is useful for
 * questions with a tight source constraint, e.g. "find me packets send from node A that get
 * dropped". When reasoning about many sources simultaneously, we have to somehow remember the
 * source, which is very expensive for a large number of sources. For queries that have to consider
 * all packets that can reach the query state, backward reachability is much more efficient.
 */
public class BDDReachabilityAnalysis {
  private final BDDPacket _bddPacket;

  // preState --> postState --> predicate
  private final Map<StateExpr, Map<StateExpr, Edge>> _edges;

  // postState --> preState --> predicate
  private final Map<StateExpr, Map<StateExpr, Edge>> _reverseEdges;

  // stateExprs that correspond to the IngressLocations of interest
  private final ImmutableSet<StateExpr> _ingressLocationStates;

  private final BDD _queryHeaderSpaceBdd;

  private Map<List<StateExpr>, BDD> _pathDB;

  BDDReachabilityAnalysis(
      BDDPacket packet,
      Set<StateExpr> ingressLocationStates,
      Map<StateExpr, Map<StateExpr, Edge>> edges,
      BDD queryHeaderSpaceBdd) {
    _bddPacket = packet;
    _edges = edges;
    _reverseEdges = computeReverseEdges(_edges);
    _ingressLocationStates = ImmutableSet.copyOf(ingressLocationStates);
    _queryHeaderSpaceBdd = queryHeaderSpaceBdd;
    _pathDB = null;
  }

  private static Map<StateExpr, Map<StateExpr, Edge>> computeReverseEdges(
      Map<StateExpr, Map<StateExpr, Edge>> edges) {
    Map<StateExpr, Map<StateExpr, Edge>> reverseEdges = new HashMap<>();
    edges.forEach(
        (preState, preStateOutEdges) ->
            preStateOutEdges.forEach(
                (postState, edge) ->
                    reverseEdges
                        .computeIfAbsent(postState, k -> new HashMap<>())
                        .put(preState, edge)));
    // freeze
    return toImmutableMap(
        reverseEdges, Entry::getKey, entry -> ImmutableMap.copyOf(entry.getValue()));
  }

  private Map<StateExpr, BDD> computeReverseReachableStates() {
    Map<StateExpr, BDD> reverseReachableStates = new HashMap<>();
    reverseReachableStates.put(Query.INSTANCE, _queryHeaderSpaceBdd);

    backwardFixpoint(reverseReachableStates);

    return ImmutableMap.copyOf(reverseReachableStates);
  }

  public Map<IngressLocation, BDD> findLoops_allpair_dp() {
    Map<StateExpr, Map<StateExpr, BDD>> reachable = new HashMap<>();

    for (StateExpr node1 : _edges.keySet()) {
      Map<StateExpr, Edge> edgeMap = _edges.get(node1);
      for (StateExpr node2 : edgeMap.keySet()) {
        Edge edge = edgeMap.get(node2);
        reachable
            .getOrDefault(node1, new HashMap<>())
            .put(node2, edge.traverseForward(_bddPacket.getFactory().one()));
      }
    }

    BDD zero = _bddPacket.getFactory().zero();
    for (StateExpr nodeK : _edges.keySet()) {
      for (StateExpr nodeI : _edges.keySet()) {
        for (StateExpr nodeJ : _edges.keySet()) {
          BDD bddNodeIToNodeK = reachable.getOrDefault(nodeI, new HashMap<>())
              .getOrDefault(nodeK, zero);
          BDD bddNodeIToNodeJ = reachable.getOrDefault(nodeI, new HashMap<>()).getOrDefault(nodeJ, zero);
          BDD bddNodeJToNodeK = reachable.getOrDefault(nodeJ, new HashMap<>()).getOrDefault(nodeK, zero);
          reachable.getOrDefault(nodeI, new HashMap<>())
              .put(
                  nodeK, bddNodeIToNodeK.or(bddNodeIToNodeJ.and(bddNodeJToNodeK))
              );
        }
      }
    }

    Map<StateExpr, BDD> loopBDDs = new HashMap<>();
    for (StateExpr node : reachable.keySet()) {
      BDD bdd = reachable.get(node).getOrDefault(node, null);
      if (bdd != null && !bdd.isZero()) {
        loopBDDs.put(node, _bddPacket.getFactory().one());
      }
    }
    return getIngressLocationBDDs(loopBDDs);
  }

  public Map<IngressLocation, BDD> findLoops_dp() {
    Map<StateExpr, Map<StateExpr, BDD>> reachable = new HashMap<>();
    Map<StateExpr, Set<StateExpr>> dirty = new HashMap<>();

    for (StateExpr node1 : _edges.keySet()) {
      Map<StateExpr, Edge> edgeMap = _edges.get(node1);
      for (StateExpr node2 : edgeMap.keySet()) {
        Edge edge = edgeMap.get(node2);
        reachable.computeIfAbsent(node1,
            k -> new HashMap<>())
        .put(node2, edge.traverseForward(_bddPacket.getFactory().one()));
        dirty.computeIfAbsent(node1, k -> new HashSet<>()).add(node2);
      }
    }

    BDD zero = _bddPacket.getFactory().zero();
    while (!dirty.isEmpty()) {
      Map<StateExpr, Map<StateExpr, BDD>> newReachable = new HashMap<>();
      for (StateExpr node1 : reachable.keySet()) {
        for (StateExpr node2 : reachable.get(node1).keySet()) {
          newReachable.computeIfAbsent(node1, k -> new HashMap<>())
              .put(node2, reachable.get(node1).get(node2));
        }
      }

      Map<StateExpr, Set<StateExpr>> newDirty = new HashMap<>();
      for (StateExpr node1 : reachable.keySet()) {
        Map<StateExpr, BDD> reachableNodes = reachable.get(node1);
        for (StateExpr node2 : reachableNodes.keySet()) {
          Map<StateExpr, Edge> node2Edges = _edges.get(node2);
          if (node2Edges == null) {
            continue;
          }

          BDD node1ToNode2 = reachable.get(node1).get(node2);
          for (StateExpr node3 : node2Edges.keySet()) {
            Edge edge = node2Edges.get(node3);
            BDD addedNode1ToNode3 = edge.traverseForward(node1ToNode2);
            if (!addedNode1ToNode3.isZero()) {
              Map<StateExpr, BDD> newNode1Map =
                  newReachable.getOrDefault(node1, new HashMap<>());
              BDD node1ToNode3 = newNode1Map.getOrDefault(node3, zero);
              BDD newNode1ToNode3 = node1ToNode3.or(addedNode1ToNode3);
              newNode1Map.put(node3, newNode1ToNode3);
              newReachable.put(node1, newNode1Map);
              if (!reachable.get(node1).getOrDefault(node3, zero).equals(newNode1ToNode3)) {
                newDirty.computeIfAbsent(node1, k -> new HashSet<>()).add(node3);
              }
            }
          }
        }
      }
      dirty = newDirty;
      reachable = newReachable;
    }

    /*
    while (!dirty.isEmpty()) {
      Map<StateExpr, Map<StateExpr, BDD>> newReachable = new HashMap<>();
      Map<StateExpr, Set<StateExpr>> newDirty = new HashMap<>();
      for (StateExpr node1 : dirty.keySet()) {
        Set<StateExpr> neighbors = dirty.get(node1);
        for (StateExpr node2 : neighbors) {
          Map<StateExpr, Edge> node2Edges = _edges.get(node2);
          if (node2Edges == null) {
            continue;
          }

          BDD node1ToNode2 = reachable.get(node1).get(node2);
          node2Edges.forEach(
              (node3, edge) -> {
                BDD addedNode1ToNode3 = edge.traverseForward(node1ToNode2);
                if (!addedNode1ToNode3.isZero()) {
                  Map<StateExpr, BDD> node1Map =
                      newReachable.getOrDefault(node1, new HashMap<>());
                  BDD node1ToNode3 = node1Map.getOrDefault(node3, zero);
                  BDD newNode1ToNode3 = node1ToNode3.or(addedNode1ToNode3);
                  if (!node1ToNode3.equals(newNode1ToNode3)) {
                    node1Map.put(node3, newNode1ToNode3);
                    newDirty.getOrDefault(node1, new HashSet<>()).add(node3);
                  }
                }
              });
        }
      }
      dirty = newDirty;
      reachable = newReachable;
    }
    */

    Map<StateExpr, BDD> loopBDDs = new HashMap<>();
    for (StateExpr node : reachable.keySet()) {
      BDD bdd = reachable.get(node).getOrDefault(node, null);
      if (bdd != null && !bdd.isZero()) {
        loopBDDs.put(node, bdd);
      }
    }
    Map<StateExpr, BDD> inLoopBDDs = new HashMap<>();
    for (StateExpr ingressNode : _ingressLocationStates) {
      for (StateExpr loopingNode : loopBDDs.keySet()) {
        BDD bdd =
            reachable
                .getOrDefault(ingressNode, new HashMap<>())
                .getOrDefault(loopingNode, zero)
                .and(loopBDDs.get(loopingNode));
        if (!bdd.isZero()) {
          inLoopBDDs.put(ingressNode, bdd);
        }
      }
    }
    return getIngressLocationBDDs(inLoopBDDs);
  }

  public Map<IngressLocation, BDD> findLoops_iterativeDFS() {
    Map<StateExpr, BDD> loopBDDs = new HashMap<>();
    // run DFS for each ingress location
    for (StateExpr node : _ingressLocationStates) {
      BDD loopBDD = findLoopsPerSource(node);
      if (loopBDD != null) {
        loopBDDs.put(node, loopBDD);
      }
    }
    // freeze
    return getIngressLocationBDDs(loopBDDs);
  }

  private BDD findLoopsPerSource(StateExpr root) {
    BDD result = _bddPacket.getFactory().zero();

    Set<StateExpr> visitedNodes = new HashSet<>();

    Set<StateExpr> inHistory = new HashSet<>();
    List<StateExpr> history = new ArrayList<>();
    List<BDD> historyBDD = new ArrayList<>();
    List<Iterator<StateExpr>> historyIteraror = new ArrayList<>();

    visitedNodes.add(root);
    inHistory.add(root);
    history.add(root);
    historyBDD.add(_bddPacket.getFactory().one());
    Map<StateExpr, Edge> postStateInEdges = _edges.get(root);
    if (postStateInEdges != null) {
      Iterator<StateExpr> iterator = postStateInEdges.keySet().iterator();
      historyIteraror.add(iterator);
    } else {
      historyIteraror.add(null);
    }

    while (!history.isEmpty()) {
      StateExpr currentNode = history.get(history.size()-1);
      BDD symbolicPacket = historyBDD.get(historyBDD.size()-1);
      Iterator<StateExpr> iterator = historyIteraror.get(historyIteraror.size()-1);

      if (iterator != null && iterator.hasNext()) {
        // there is a next node to traverse
        StateExpr nextNode = iterator.next();
        Edge edge = _edges.get(currentNode).get(nextNode);
        BDD nextSymbolicPacket = edge.traverseForward(symbolicPacket);
        //BDD nextSymbolicPacket = _bddPacket.getFactory().one();

        if (!nextSymbolicPacket.isZero()) {
          if (inHistory.contains(nextNode)) {
            // find a loop
            result = result.or(nextSymbolicPacket);
            //return nextSymbolicPacket;
          } else { //if (!visitedNodes.contains(nextNode)) {
            visitedNodes.add(nextNode);
            // new node should be added
            inHistory.add(nextNode);
            history.add(nextNode);
            historyBDD.add(nextSymbolicPacket);
            Map<StateExpr, Edge> nextPostStateInEdges = _edges.get(nextNode);
            if (nextPostStateInEdges != null) {
              Iterator<StateExpr> nextIterator = nextPostStateInEdges.keySet().iterator();
              historyIteraror.add(nextIterator);
            } else {
              historyIteraror.add(null);
            }
          }
        } else {
          // no packets left; do nothing
        }
      } else {
        // no next node to traverse; need to back track
        history.remove(history.size() - 1);
        historyBDD.remove(historyBDD.size() - 1);
        historyIteraror.remove(historyIteraror.size() - 1);
        inHistory.remove(currentNode);
      }
    }
    return result;
  }

  public Map<IngressLocation, BDD> getLoopBDDs() {
    long startTime = System.currentTimeMillis();
    if (_pathDB == null) {
      _pathDB = buildPathDB();
      long endBuildingDB = System.currentTimeMillis();
      System.out.println("pathDB building time (millisecond) = " + (endBuildingDB-startTime));
    }

    Map<StateExpr, BDD> loopBDDs = new HashMap<>();

    long startSelectionTime = System.currentTimeMillis();
    _pathDB
        .entrySet()
        .stream()
        .filter(
            entry -> {
              List<StateExpr> path = entry.getKey();

              StateExpr lastHop = path.get(path.size() - 1);
              return path.subList(0, path.size()-1).contains(lastHop);
            })
        .forEach(
            entry -> {
              StateExpr firstNode = entry.getKey().get(0);
              loopBDDs.putIfAbsent(firstNode, entry.getValue());
            });
    long endSelectionTime = System.currentTimeMillis();
    System.out.println("Seletction time (milliseconds) = " + (endSelectionTime-startSelectionTime));

    return getIngressLocationBDDs(loopBDDs);
  }

  public Map<List<StateExpr>, BDD> buildPathDB() {
    HashMap<List<StateExpr>, BDD> pathDB = new HashMap<>();
    // run DFS for each ingress location
    for (StateExpr node : _ingressLocationStates) {
      symbolicRun(_bddPacket.getFactory().one(), new ArrayList<>(), new HashSet<>(), node, pathDB);
    }
    // freeze
    return toImmutableMap(pathDB, entry -> ImmutableList.copyOf(entry.getKey()), Entry::getValue);
  }

  private void symbolicRun(
      BDD symbolicPacket,
      List<StateExpr> history,
      Set<StateExpr> visitedNodes,
      StateExpr currentNode,
      Map<List<StateExpr>, BDD> pathDB) {
    if (visitedNodes.contains(currentNode)) {
      // Loop detected
      history.add(currentNode);
      pathDB.put(ImmutableList.copyOf(history), symbolicPacket);
      history.remove(history.size()-1);
    } else {
      history.add(currentNode);
      visitedNodes.add(currentNode);
      Map<StateExpr, Edge> postStateInEdges = _edges.get(currentNode);
      if (postStateInEdges != null) {
        for (StateExpr nextNode : postStateInEdges.keySet()) {
          Edge edge = postStateInEdges.get(nextNode);
          BDD nextSymbolicPacket = edge.traverseForward(symbolicPacket);

          if (!nextSymbolicPacket.isZero()) {
            symbolicRun(nextSymbolicPacket, history, visitedNodes, nextNode, pathDB);
          } else {
            //pathDB.put(ImmutableList.copyOf(history), symbolicPacket);
          }
        }
      } else {
        //pathDB.put(ImmutableList.copyOf(history), symbolicPacket);
      }
      history.remove(history.size()-1);
      visitedNodes.remove(currentNode);
    }
  }

  private void backwardFixpoint(Map<StateExpr, BDD> reverseReachableStates) {
    Set<StateExpr> dirty = ImmutableSet.copyOf(reverseReachableStates.keySet());

    while (!dirty.isEmpty()) {
      Set<StateExpr> newDirty = new HashSet<>();

      dirty.forEach(
          postState -> {
            Map<StateExpr, Edge> postStateInEdges = _reverseEdges.get(postState);
            if (postStateInEdges == null) {
              // postState has no in-edges
              return;
            }

            BDD postStateBDD = reverseReachableStates.get(postState);
            postStateInEdges.forEach(
                (preState, edge) -> {
                  BDD result = edge.traverseBackward(postStateBDD);
                  if (result.isZero()) {
                    return;
                  }

                  // update preState BDD reverse-reachable from leaf
                  BDD oldReach = reverseReachableStates.get(preState);
                  BDD newReach = oldReach == null ? result : oldReach.or(result);
                  if (oldReach == null || !oldReach.equals(newReach)) {
                    reverseReachableStates.put(preState, newReach);
                    newDirty.add(preState);
                  }
                });
          });

      dirty = newDirty;
    }
  }

  private Map<StateExpr, BDD> reachableInNRounds(int numRounds) {
    BDD one = _bddPacket.getFactory().one();

    // All ingress locations are reachable in 0 rounds.
    Map<StateExpr, BDD> reachableInNRounds =
        toImmutableMap(_ingressLocationStates, Function.identity(), k -> one);

    for (int round = 0; !reachableInNRounds.isEmpty() && round < numRounds; round++) {
      reachableInNRounds = propagate(reachableInNRounds);
    }
    return reachableInNRounds;
  }

  /*
   * Detect infinite routing loops in the network.
   */
  public Map<IngressLocation, BDD> detectLoops() {
    long startTime = System.currentTimeMillis();
    /*
     * Run enough rounds to exceed the max TTL (255). It takes at most 5 iterations to go between
     * hops:
     * PreInInterface -> PostInVrf -> PreOutVrf -> PreOutEdge -> PreOutEdgePostNat -> PreInInterface
     *
     * Since we don't model TTL, all packets on loops will loop forever. But most paths will stop
     * long before numRounds. What's left will be a few candidate location/headerspace pairs that
     * may be on loops. In practice this is most likely way more iterations than necessary.
     */
    int numRounds = 256 * 5;
    Map<StateExpr, BDD> reachableInNRounds = reachableInNRounds(numRounds);

    /*
     * Identify which of the candidates are actually on loops
     */
    Map<StateExpr, BDD> loopBDDs =
        reachableInNRounds
            .entrySet()
            .stream()
            .filter(entry -> confirmLoop(entry.getKey(), entry.getValue()))
            .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

    /*
     * Run backward to find the ingress locations/headerspaces that lead to loops.
     */
    backwardFixpoint(loopBDDs);
    long endTime = System.currentTimeMillis();
    System.out.println("detectLoops time (millisecond) = " + (endTime-startTime));
    /*
     * Extract the ingress location BDDs.
     */
    return getIngressLocationBDDs(loopBDDs);
  }

  private Map<StateExpr, BDD> propagate(Map<StateExpr, BDD> bdds) {
    BDD zero = _bddPacket.getFactory().zero();
    Map<StateExpr, BDD> newReachableInNRounds = new HashMap<>();
    bdds.forEach(
        (source, sourceBdd) ->
            _edges
                .getOrDefault(source, ImmutableMap.of())
                .forEach(
                    (target, edge) -> {
                      BDD targetBdd = newReachableInNRounds.getOrDefault(target, zero);
                      BDD newTragetBdd = targetBdd.or(edge.traverseForward(sourceBdd));
                      if (!newTragetBdd.isZero()) {
                        newReachableInNRounds.put(target, newTragetBdd);
                      }
                    }));
    return newReachableInNRounds;
  }

  /**
   * Run BFS from one step past the initial state. Each round, check if the initial state has been
   * reached yet.
   */
  private boolean confirmLoop(StateExpr stateExpr, BDD bdd) {
    Map<StateExpr, BDD> reachable = propagate(ImmutableMap.of(stateExpr, bdd));
    Set<StateExpr> dirty = new HashSet<>(reachable.keySet());

    BDD zero = _bddPacket.getFactory().zero();
    while (!dirty.isEmpty()) {
      Set<StateExpr> newDirty = new HashSet<>();

      dirty.forEach(
          preState -> {
            Map<StateExpr, Edge> preStateOutEdges = _edges.get(preState);
            if (preStateOutEdges == null) {
              // preState has no out-edges
              return;
            }

            BDD preStateBDD = reachable.get(preState);
            preStateOutEdges.forEach(
                (postState, edge) -> {
                  BDD result = edge.traverseForward(preStateBDD);
                  if (result.isZero()) {
                    return;
                  }

                  // update postState BDD reverse-reachable from leaf
                  BDD oldReach = reachable.getOrDefault(postState, zero);
                  BDD newReach = oldReach == null ? result : oldReach.or(result);
                  if (oldReach == null || !oldReach.equals(newReach)) {
                    reachable.put(postState, newReach);
                    newDirty.add(postState);
                  }
                });
          });

      dirty = newDirty;
      if (dirty.contains(stateExpr)) {
        if (!reachable.get(stateExpr).and(bdd).isZero()) {
          return true;
        }
      }
    }
    return false;
  }

  public BDDPacket getBDDPacket() {
    return _bddPacket;
  }

  public Map<IngressLocation, BDD> getIngressLocationReachableBDDs() {
    Map<StateExpr, BDD> reverseReachableStates = computeReverseReachableStates();
    return getIngressLocationBDDs(reverseReachableStates);
  }

  private Map<IngressLocation, BDD> getIngressLocationBDDs(
      Map<StateExpr, BDD> reverseReachableStates) {
    BDD zero = _bddPacket.getFactory().zero();
    return _ingressLocationStates
        .stream()
        .collect(
            ImmutableMap.toImmutableMap(
                BDDReachabilityAnalysis::toIngressLocation,
                root -> reverseReachableStates.getOrDefault(root, zero)));
  }

  @VisibleForTesting
  static IngressLocation toIngressLocation(StateExpr stateExpr) {
    Preconditions.checkArgument(
        stateExpr instanceof OriginateVrf || stateExpr instanceof OriginateInterfaceLink);

    if (stateExpr instanceof OriginateVrf) {
      OriginateVrf originateVrf = (OriginateVrf) stateExpr;
      return IngressLocation.vrf(originateVrf.getHostname(), originateVrf.getVrf());
    } else {
      OriginateInterfaceLink originateInterfaceLink = (OriginateInterfaceLink) stateExpr;
      return IngressLocation.interfaceLink(
          originateInterfaceLink.getHostname(), originateInterfaceLink.getIface());
    }
  }

  @VisibleForTesting
  Map<StateExpr, Map<StateExpr, Edge>> getEdges() {
    return _edges;
  }
}
