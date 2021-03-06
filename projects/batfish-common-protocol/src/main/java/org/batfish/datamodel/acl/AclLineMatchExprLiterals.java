package org.batfish.datamodel.acl;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.batfish.datamodel.AclLine;
import org.batfish.datamodel.ExprAclLine;
import org.batfish.datamodel.IpAccessList;

/**
 * A visitor for ACL lines that collects up the set of all atomic predicates, which we call literals
 * (though typically that term also includes the negation of atomic predicates), within a given
 * line.
 */
public class AclLineMatchExprLiterals
    implements GenericAclLineMatchExprVisitor<Void>, GenericAclLineVisitor<Void> {

  private Set<AclLineMatchExpr> _literals;

  private AclLineMatchExprLiterals() {
    _literals = Collections.newSetFromMap(new IdentityHashMap<>());
  }

  public static IdentityHashMap<AclLineMatchExpr, AclLineIndex> literalsToLines(IpAccessList acl) {
    return literalsToLines(Collections.singleton(acl));
  }

  // Create a map from each literal in the given acls to the acl and index that it came from.
  public static IdentityHashMap<AclLineMatchExpr, AclLineIndex> literalsToLines(
      Collection<IpAccessList> acls) {
    IdentityHashMap<AclLineMatchExpr, AclLineIndex> literalsToLines = new IdentityHashMap<>();
    acls.forEach(
        currAcl -> {
          List<AclLine> lines = currAcl.getLines();
          IntStream.range(0, lines.size())
              .forEach(
                  i ->
                      AclLineMatchExprLiterals.getLiterals(lines.get(i))
                          .forEach(lit -> literalsToLines.put(lit, new AclLineIndex(currAcl, i))));
        });
    return literalsToLines;
  }

  /** This method is the public entry point for the visitor. */
  public static Set<AclLineMatchExpr> getLiterals(AclLine line) {
    AclLineMatchExprLiterals aclLineMatchExprLiterals = new AclLineMatchExprLiterals();
    aclLineMatchExprLiterals.visit(line);
    return aclLineMatchExprLiterals._literals;
  }

  /* AclLine visit methods */

  @Override
  public Void visitExprAclLine(ExprAclLine exprAclLine) {
    return visit(exprAclLine.getMatchCondition());
  }

  /* AclLineMatchExpr visit methods */

  @Override
  public Void visitAndMatchExpr(AndMatchExpr andMatchExpr) {
    andMatchExpr.getConjuncts().forEach(this::visit);
    return null;
  }

  @Override
  public Void visitFalseExpr(FalseExpr falseExpr) {
    return null;
  }

  @Override
  public Void visitMatchHeaderSpace(MatchHeaderSpace matchHeaderSpace) {
    _literals.add(matchHeaderSpace);
    return null;
  }

  @Override
  public Void visitMatchSrcInterface(MatchSrcInterface matchSrcInterface) {
    _literals.add(matchSrcInterface);
    return null;
  }

  @Override
  public Void visitNotMatchExpr(NotMatchExpr notMatchExpr) {
    // normalization retains negation on literals but not in general, so we recurse either way
    this.visit(notMatchExpr.getOperand());
    return null;
  }

  @Override
  public Void visitOriginatingFromDevice(OriginatingFromDevice originatingFromDevice) {
    _literals.add(originatingFromDevice);
    return null;
  }

  @Override
  public Void visitOrMatchExpr(OrMatchExpr orMatchExpr) {
    orMatchExpr.getDisjuncts().forEach(this::visit);
    return null;
  }

  @Override
  public Void visitPermittedByAcl(PermittedByAcl permittedByAcl) {
    return null;
  }

  @Override
  public Void visitTrueExpr(TrueExpr trueExpr) {
    return null;
  }
}
