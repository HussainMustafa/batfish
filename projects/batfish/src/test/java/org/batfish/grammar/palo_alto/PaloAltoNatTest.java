package org.batfish.grammar.palo_alto;

import static org.batfish.main.BatfishTestUtils.getBatfish;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import javax.annotation.Nonnull;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Flow;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.flow.Trace;
import org.batfish.main.Batfish;
import org.batfish.main.BatfishTestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class PaloAltoNatTest {
  private static final String TESTCONFIGS_PREFIX = "org/batfish/grammar/palo_alto/testconfigs/";

  @Rule public TemporaryFolder _folder = new TemporaryFolder();

  @Rule public ExpectedException _thrown = ExpectedException.none();

  private @Nonnull Configuration parseConfig(String hostname) {
    try {
      Map<String, Configuration> configs = parseTextConfigs(hostname);
      assertThat(configs, hasKey(hostname));
      return configs.get(hostname);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Map<String, Configuration> parseTextConfigs(String... configurationNames)
      throws IOException {
    return getBatfishForConfigurationNames(configurationNames).loadConfigurations();
  }

  private Batfish getBatfishForConfigurationNames(String... configurationNames) throws IOException {
    String[] names =
        Arrays.stream(configurationNames).map(s -> TESTCONFIGS_PREFIX + s).toArray(String[]::new);
    return BatfishTestUtils.getBatfishForTextConfigs(_folder, names);
  }

  @Test
  public void testDestNat() throws IOException {
    // Test destination NAT for traffic from outside zone to inside zone
    Configuration c = parseConfig("destination-nat");
    String inside1Name = "ethernet1/1.1"; // 1.1.1.3/31
    String inside2Name = "ethernet1/1.2"; // 1.1.2.3/31
    String outside1Name = "ethernet1/2.1"; // 1.2.1.3/31
    String outside2Name = "ethernet1/2.2"; // 1.2.2.3/31
    assertThat(
        c.getAllInterfaces().keySet(),
        containsInAnyOrder(
            "ethernet1/1", inside1Name, inside2Name, "ethernet1/2", outside1Name, outside2Name));

    Interface inside1 = c.getAllInterfaces().get(inside1Name);
    Interface inside2 = c.getAllInterfaces().get(inside2Name);
    Interface outside1 = c.getAllInterfaces().get(outside1Name);
    Interface outside2 = c.getAllInterfaces().get(outside2Name);

    String outside1Policy = outside1.getRoutingPolicyName();
    String outside2Policy = outside2.getRoutingPolicyName();

    // Interfaces in OUTSIDE zone have packet policy
    assertThat(outside1Policy, notNullValue());
    assertThat(outside2Policy, notNullValue());

    Batfish batfish = getBatfish(ImmutableSortedMap.of(c.getHostname(), c), _folder);
    batfish.computeDataPlane();

    Flow outsideToInsideNat =
        Flow.builder()
            .setTag("test")
            .setIngressNode(c.getHostname())
            .setIngressInterface(outside1Name)
            .setDstIp(Ip.parse("1.1.1.2"))
            .setSrcIp(Ip.parse("1.2.1.2"))
            .build();
    Flow outsideToInsideBadSrcIp =
        Flow.builder()
            .setTag("test")
            .setIngressNode(c.getHostname())
            .setIngressInterface(outside1Name)
            .setDstIp(Ip.parse("1.1.1.2"))
            .setSrcIp(Ip.parse("1.2.1.200"))
            .build();
    Flow outsideToInsideBadDstIp =
        Flow.builder()
            .setTag("test")
            .setIngressNode(c.getHostname())
            .setIngressInterface(outside1Name)
            .setDstIp(Ip.parse("1.1.1.200"))
            .setSrcIp(Ip.parse("1.2.1.2"))
            .build();
    Flow outsideToInsideBadIngressIface =
        Flow.builder()
            .setTag("test")
            .setIngressNode(c.getHostname())
            .setIngressInterface(inside2Name)
            .setDstIp(Ip.parse("1.1.1.2"))
            .setSrcIp(Ip.parse("1.2.1.2"))
            .build();

    SortedMap<Flow, List<Trace>> traces =
        batfish
            .getTracerouteEngine()
            .computeTraces(
                ImmutableSet.of(
                    outsideToInsideNat,
                    outsideToInsideBadSrcIp,
                    outsideToInsideBadDstIp,
                    outsideToInsideBadIngressIface),
                false);

    assertThat(c, notNullValue());
  }

  @Test
  public void testPanoramaDestNat() {
    Configuration c = parseConfig("destination-nat-panorama");
    assertThat(c, notNullValue());
  }
}
