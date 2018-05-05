package org.ovirt.engine.core.bll.validator;

import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ovirt.engine.core.bll.validator.ValidationResultMatchers.failsWith;
import static org.ovirt.engine.core.bll.validator.ValidationResultMatchers.isValid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.ovirt.engine.core.bll.ValidationResult;
import org.ovirt.engine.core.bll.network.cluster.ManagementNetworkUtil;
import org.ovirt.engine.core.common.businessentities.IscsiBond;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VmTemplate;
import org.ovirt.engine.core.common.businessentities.network.Network;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.IscsiBondDao;
import org.ovirt.engine.core.dao.VdsDao;
import org.ovirt.engine.core.dao.VmDao;
import org.ovirt.engine.core.dao.VmTemplateDao;
import org.ovirt.engine.core.dao.network.NetworkDao;
import org.ovirt.engine.core.di.InjectorRule;
import org.ovirt.engine.core.utils.RandomUtils;
import org.ovirt.engine.core.utils.ReplacementUtils;

@RunWith(MockitoJUnitRunner.class)
public class NetworkValidatorTest {

    private static final String NAMEABLE_NAME = "nameable";
    private static final String DEFAULT_NETWORK_NAME = "mynetwork";
    private static final String OTHER_NETWORK_NAME = "myothernetwork";
    private static final String EXTERNAL_NETWORK_NAME = "external_network";
    private static final Guid DEFAULT_GUID = Guid.newGuid();
    private static final Guid OTHER_GUID = Guid.newGuid();

    @Rule
    public InjectorRule injectorRule = new InjectorRule();

    @Mock
    private NetworkDao networkDao;

    @Mock
    private Network network;

    @Mock
    private ManagementNetworkUtil managementNetworkUtil;

    @Mock
    private VmDao vmDao;

    private List<Network> networks = new ArrayList<>();
    private NetworkValidator validator;

    @Before
    public void setup() {

        // spy on attempts to access the database
        validator = new NetworkValidator(network);
        injectorRule.bind(ManagementNetworkUtil.class, managementNetworkUtil);
        injectorRule.bind(NetworkDao.class, networkDao);
        injectorRule.bind(VmDao.class, vmDao);

        // mock their getters
        when(networkDao.getAllForDataCenter(any())).thenReturn(networks);
    }

    @Test
    public void networkSet() throws Exception {
        assertThat(validator.networkIsSet(Guid.newGuid()), isValid());
    }

    @Test
    public void networkNull() throws Exception {
        validator = new NetworkValidator(null);
        assertThat(validator.networkIsSet(Guid.newGuid()), failsWith(EngineMessage.NETWORK_HAVING_ID_NOT_EXISTS));
    }

    private void stpTest(Matcher<ValidationResult> matcher, boolean vmNetwork, boolean stp) {
        when(network.isVmNetwork()).thenReturn(vmNetwork);
        when(network.getStp()).thenReturn(stp);

        assertThat(validator.stpForVmNetworkOnly(), matcher);
    }

    @Test
    public void stpWhenVmNetwork() throws Exception {
        stpTest(isValid(), true, true);
    }

    @Test
    public void noStpWhenVmNetwork() throws Exception {
        stpTest(isValid(), true, false);
    }

    @Test
    public void stpWhenNonVmNetwork() throws Exception {
        stpTest(failsWith(EngineMessage.NON_VM_NETWORK_CANNOT_SUPPORT_STP), false, true);
    }

    @Test
    public void noStpWhenNonVmNetwork() throws Exception {
        stpTest(isValid(), false, false);
    }

    @Test
    public void mtuValid() {
        assertThat(validator.mtuValid(), isValid());
    }

    private void networkPrefixValidTest(Matcher<ValidationResult> matcher, String networkName) {
        when(network.getName()).thenReturn(networkName);

        assertThat(validator.networkPrefixValid(), matcher);
    }

    @Test
    public void networkPrefixBond() throws Exception {
        networkPrefixValidTest(failsWith(EngineMessage.NETWORK_CANNOT_CONTAIN_BOND_NAME), "bond0");
    }

    @Test
    public void networkPrefixInnocent() throws Exception {
        networkPrefixValidTest(isValid(), DEFAULT_NETWORK_NAME);
    }

    private void networkNameAvailableTest(Matcher<ValidationResult> matcher, List<Network> networks) {
        this.networks.addAll(networks);
        when(network.getName()).thenReturn(DEFAULT_NETWORK_NAME);
        when(network.getId()).thenReturn(DEFAULT_GUID);

        assertThat(validator.networkNameNotUsed(), matcher);
    }

    private void notIscsiBondNetworkTest(Matcher<ValidationResult> matcher, List<IscsiBond> iscsiBonds) {
        IscsiBondDao iscsiBondDao = mock(IscsiBondDao.class);
        when(iscsiBondDao.getIscsiBondsByNetworkId(any())).thenReturn(iscsiBonds);
        injectorRule.bind(IscsiBondDao.class, iscsiBondDao);
        assertThat(validator.notIscsiBondNetwork(), matcher);
    }

    private static List<Network> getSingletonNamedNetworkList(String networkName, Guid networkId) {
        Network network = mock(Network.class);
        when(network.getName()).thenReturn(networkName);
        when(network.getId()).thenReturn(networkId);
        return Collections.singletonList(network);
    }

    private static List<IscsiBond> getIscsiBondList() {
        List<IscsiBond> iscsiBondList = new ArrayList<>();
        IscsiBond iscsiBond = new IscsiBond();
        iscsiBond.setId(Guid.newGuid());
        iscsiBond.setName("IscsiBond name");
        iscsiBondList.add(iscsiBond);
        return iscsiBondList;
    }

    @Test
    public void noIscsiBondsForNetowrkTest() throws Exception {
        notIscsiBondNetworkTest(isValid(), Collections.emptyList());
    }

    @Test
    public void existingIscsiBondsForNetowrkTest() throws Exception {
        notIscsiBondNetworkTest(failsWith(EngineMessage.NETWORK_CANNOT_REMOVE_ISCSI_BOND_NETWORK), getIscsiBondList());
    }

    @Test
    public void networkNameNoNetworks() throws Exception {
        networkNameAvailableTest(isValid(), Collections.emptyList());
    }

    @Test
    public void networkNameAvailable() throws Exception {
        networkNameAvailableTest(isValid(), getSingletonNamedNetworkList(OTHER_NETWORK_NAME, OTHER_GUID));
    }

    @Test
    public void networkNameTakenByVdsmName() {
        when(network.getName()).thenReturn("vdsm-name");
        when(network.getId()).thenReturn(DEFAULT_GUID);

        Network network2 = new Network();
        network2.setVdsmName("vdsm-name");
        network2.setName("vdsm-name");
        network2.setId(OTHER_GUID);

        when(networkDao.getAllForDataCenter(any())).thenReturn(Arrays.asList(network, network2));
        assertThat(validator.networkNameNotUsedAsVdsmName(), failsWith(EngineMessage.NETWORK_NAME_USED_AS_VDSM_NETWORK_NAME));
    }

    @Test
    public void networkNameNotTakenByVdsmName() {
        when(network.getName()).thenReturn(DEFAULT_NETWORK_NAME);
        when(network.getId()).thenReturn(DEFAULT_GUID);

        Network network2 = new Network();
        network2.setVdsmName("vdsm-name");
        network2.setId(OTHER_GUID);

        when(networkDao.getAllForDataCenter(any())).thenReturn(Arrays.asList(network, network2));
        assertThat(validator.networkNameNotUsedAsVdsmName(), isValid());
    }

    @Test
    public void networkNameTakenByDifferentNetwork() throws Exception {
        networkNameAvailableTest(failsWith(EngineMessage.ACTION_TYPE_FAILED_NETWORK_NAME_IN_USE),
                getSingletonNamedNetworkList(DEFAULT_NETWORK_NAME, OTHER_GUID));
    }

    @Test
    public void networkNameTakenCaseSensitivelyByDifferentNetwork() throws Exception {
        networkNameAvailableTest(isValid(),
                getSingletonNamedNetworkList(DEFAULT_NETWORK_NAME.toUpperCase(), OTHER_GUID));
    }

    @Test
    public void networkNameTakenBySameNetwork() throws Exception {
        networkNameAvailableTest(isValid(),
                getSingletonNamedNetworkList(DEFAULT_NETWORK_NAME, DEFAULT_GUID));
    }

    private static Matcher<ValidationResult> failsWithOneNetworkInUse() {
        return failsWith(EngineMessage.ACTION_TYPE_FAILED_NETWORK_IN_ONE_USE);
    }

    private static Matcher<ValidationResult> failsWithManyNetworkInUse() {
        return failsWith(EngineMessage.ACTION_TYPE_FAILED_NETWORK_IN_MANY_USES);
    }

    private void networkNotUsedByVmsTest(Matcher<ValidationResult> matcher, List<VM> vms) {
        when(vmDao.getAllForNetwork(any())).thenReturn(vms);
        assertThat(validator.networkNotUsedByVms(), matcher);
    }

    @Test
    public void networkNotInUseByVms() throws Exception {
        networkNotUsedByVmsTest(isValid(), Collections.emptyList());
    }

    @Test
    public void networkInUseByOneVm() throws Exception {
        VM vm = mock(VM.class);
        when(vm.getName()).thenReturn(NAMEABLE_NAME);

        networkNotUsedByVmsTest(failsWithOneNetworkInUse(), Collections.singletonList(vm));
    }

    @Test
    public void networkInUseByManyVms() throws Exception {
        VM vm1 = mock(VM.class);
        when(vm1.getName()).thenReturn(NAMEABLE_NAME+1);

        VM vm2 = mock(VM.class);
        when(vm2.getName()).thenReturn(NAMEABLE_NAME+2);

        networkNotUsedByVmsTest(failsWithManyNetworkInUse(), Arrays.asList(vm1, vm2));
    }

    private void networkNotUsedByHostsTest(Matcher<ValidationResult> matcher, List<VDS> hosts) {
        VdsDao hostDao = mock(VdsDao.class);
        when(hostDao.getAllForNetwork(any())).thenReturn(hosts);
        injectorRule.bind(VdsDao.class, hostDao);
        assertThat(validator.networkNotUsedByHosts(), matcher);
    }

    @Test
    public void networkNotInUseByHosts() throws Exception {
        networkNotUsedByHostsTest(isValid(), Collections.emptyList());
    }

    @Test
    public void networkInUseByOneHost() throws Exception {
        VDS host = mock(VDS.class);
        when(host.getName()).thenReturn(NAMEABLE_NAME);

        networkNotUsedByHostsTest(failsWithOneNetworkInUse(), Collections.singletonList(host));
    }

    @Test
    public void networkInUseByManyHosts() throws Exception {
        VDS host1 = mock(VDS.class);
        when(host1.getName()).thenReturn(NAMEABLE_NAME+1);

        VDS host2 = mock(VDS.class);
        when(host2.getName()).thenReturn(NAMEABLE_NAME+2);

        networkNotUsedByHostsTest(failsWithManyNetworkInUse(), Arrays.asList(host1, host2));
    }

    private void networkNotUsedByTemplatesTest(Matcher<ValidationResult> matcher, List<VmTemplate> templates) {
        VmTemplateDao templateDao = mock(VmTemplateDao.class);
        when(templateDao.getAllForNetwork(any())).thenReturn(templates);
        injectorRule.bind(VmTemplateDao.class, templateDao);
        assertThat(validator.networkNotUsedByTemplates(), matcher);
    }

    @Test
    public void networkNotInUseByTemplates() throws Exception {
        networkNotUsedByTemplatesTest(isValid(), Collections.emptyList());
    }

    @Test
    public void networkInUseByOneTemplate() throws Exception {
        VmTemplate template = mock(VmTemplate.class);
        when(template.getName()).thenReturn(NAMEABLE_NAME);

        networkNotUsedByTemplatesTest(failsWithOneNetworkInUse(), Collections.singletonList(template));
    }

    @Test
    public void networkInUseByManyTemplates() throws Exception {
        VmTemplate template1 = mock(VmTemplate.class);
        when(template1.getName()).thenReturn(NAMEABLE_NAME+1);

        VmTemplate template2 = mock(VmTemplate.class);
        when(template2.getName()).thenReturn(NAMEABLE_NAME+2);

        networkNotUsedByTemplatesTest(failsWithManyNetworkInUse(), Arrays.asList(template1, template2));
    }

    @Test
    public void networkNotLabeled() throws Exception {
        assertThat(validator.notLabeled(), isValid());
    }

    @Test
    public void networkLabeled() throws Exception {
        when(network.getLabel()).thenReturn(RandomUtils.instance().nextPropertyString(10));
        assertThat(validator.notLabeled(), failsWith(EngineMessage.ACTION_TYPE_FAILED_NETWORK_ALREADY_LABELED));
    }

    @Test
    public void testNotExternalNetworkFailsForExternalNetwork() throws Exception {
        when(network.isExternal()).thenReturn(true);
        assertThat(validator.notExternalNetwork(), failsWith(EngineMessage.ACTION_TYPE_FAILED_NOT_SUPPORTED_FOR_EXTERNAL_NETWORK));
    }

    @Test
    public void testNotExternalNetworkSucceedsForNonExternalNetwork() throws Exception {
        when(network.isExternal()).thenReturn(false);
        assertThat(validator.notExternalNetwork(), isValid());
    }

    @Test
    public void testNotManagementNetworkPositive() {
        when(network.getId()).thenReturn(DEFAULT_GUID);
        when(managementNetworkUtil.isManagementNetwork(DEFAULT_GUID)).thenReturn(true);
        assertThat(validator.notManagementNetwork(), failsWith(EngineMessage.NETWORK_CANNOT_REMOVE_MANAGEMENT_NETWORK));
    }

    @Test
    public void testNotManagementNetworkNegative() {
        when(network.getId()).thenReturn(DEFAULT_GUID);
        when(managementNetworkUtil.isManagementNetwork(DEFAULT_GUID)).thenReturn(false);
        assertThat(validator.notManagementNetwork(), isValid());
    }

    @Test
    public void testNotLinkedToExternalNetworkPositive() {
        when(network.getId()).thenReturn(DEFAULT_GUID);
        when(networkDao.getAllExternalNetworksLinkedToPhysicalNetwork(eq(DEFAULT_GUID))).thenReturn(Collections.emptyList());
        assertThat(validator.notLinkedToExternalNetwork(), isValid());
    }

    @Test
    public void testNotLinkedToExternalNetworkNegative() {
        Network externalNetwork = new Network();
        externalNetwork.setName(EXTERNAL_NETWORK_NAME);
        List<Network> externalNetworkList = Collections.singletonList(externalNetwork);
        String linkedExternalNetworkNames = externalNetworkList.stream()
                .map(Network::getName)
                .collect(joining(", "));

        when(network.getId()).thenReturn(DEFAULT_GUID);
        when(networkDao.getAllExternalNetworksLinkedToPhysicalNetwork(eq(DEFAULT_GUID))).thenReturn(externalNetworkList);
        assertThat(validator.notLinkedToExternalNetwork(),
                failsWith(EngineMessage.ACTION_TYPE_FAILED_CANNOT_REMOVE_PHYSICAL_NETWORK_LINKED_TO_EXTERNAL_NETWORK,
                        ReplacementUtils.createSetVariableString(NetworkValidator.NETWORK_LIST_REPLACEMENT,
                                linkedExternalNetworkNames)));
    }
}
