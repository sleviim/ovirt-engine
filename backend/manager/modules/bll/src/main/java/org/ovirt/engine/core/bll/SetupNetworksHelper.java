package org.ovirt.engine.core.bll;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.common.action.SetupNetworksParameters;
import org.ovirt.engine.core.common.businessentities.Entities;
import org.ovirt.engine.core.common.businessentities.Network;
import org.ovirt.engine.core.common.businessentities.NetworkBootProtocol;
import org.ovirt.engine.core.common.businessentities.VdsNetworkInterface;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.VdcBllMessages;
import org.ovirt.engine.core.dal.dbbroker.DbFacade;
import org.ovirt.engine.core.utils.NetworkUtils;

public class SetupNetworksHelper {
    private SetupNetworksParameters params;
    private Guid vdsGroupId;
    private Map<VdcBllMessages, List<String>> violations = new HashMap<VdcBllMessages, List<String>>();
    private Map<String, VdsNetworkInterface> existingIfaces;
    private Map<String, Network> existingClusterNetworks;

    private List<Network> modifiedNetworks = new ArrayList<Network>();
    private List<String> removedNetworks = new ArrayList<String>();
    private Map<String, VdsNetworkInterface> modifiedBonds = new HashMap<String, VdsNetworkInterface>();
    private Set<String> removedBonds = new HashSet<String>();

    /** All interface`s names that were processed by the helper. */
    private Set<String> ifaceNames = new HashSet<String>();

    /** Map of all bonds which were processed by the helper. Key = bond name, Value = list of slave NICs. */
    private Map<String, List<VdsNetworkInterface>> bonds = new HashMap<String, List<VdsNetworkInterface>>();

    /** All network`s names that are attached to some sort of interface. */
    private Set<String> attachedNetworksNames = new HashSet<String>();

    public SetupNetworksHelper(SetupNetworksParameters parameters, Guid vdsGroupId) {
        params = parameters;
        this.vdsGroupId = vdsGroupId;
    }

    /**
     * validate and extract data from the list of interfaces sent. The general flow is:
     * <ul>
     * <li>create mapping of existing the current topology - interfaces and logical networks.
     * <li>create maps for networks bonds and bonds-slaves.
     * <li>iterate over the interfaces and extract network/bond/slave info as we go.
     * <li>validate the extracted information by using the pre-build mappings of the current topology.
     * <li>store and encapsulate the extracted lists to later be fetched by the calling command.
     * <li>error messages are aggregated
     * </ul>
     * TODO add fail-fast to exist on the first validation error.
     *
     * @return List of violations encountered (if none, list is empty).
     */
    public List<String> validate() {
        for (VdsNetworkInterface iface : params.getInterfaces()) {
            String name = iface.getName();
            if (addInterfaceToProcessedList(iface)) {
                if (isBond(iface)) {
                    extractBondIfModified(iface, name);
                } else {
                    if (StringUtils.isNotBlank(iface.getBondName())) {
                        extractBondSlave(iface);
                    }

                    // validate the nic exists on host
                    String nameWithoutVlanId = NetworkUtils.StripVlan(name);
                    if (!getExistingIfaces().containsKey(nameWithoutVlanId)) {
                        addViolation(VdcBllMessages.NETWORK_INTERFACES_DONT_EXIST, nameWithoutVlanId);
                    }
                }

                // validate and extract to network map
                if (violations.isEmpty() && StringUtils.isNotBlank(iface.getNetworkName())) {
                    extractNetwork(iface);
                }
            }
        }

        validateBondSlavesCount();
        extractRemovedNetworks();
        extractRemovedBonds();
        detectSlaveChanges();

        return translateViolations();
    }

    private void addViolation(VdcBllMessages violation) {
        addViolation(violation, null);
    }

    private void addViolation(VdcBllMessages violation, String violatingEntity) {
        List<String> violatingEntities = violations.get(violation);
        if (violatingEntities == null) {
            violatingEntities = new ArrayList<String>();
            violations.put(violation, violatingEntities);
        }

        violatingEntities.add(violatingEntity);
    }

    private List<String> translateViolations() {
        List<String> violationMessages = new ArrayList<String>(violations.size() * 2);
        for (Map.Entry<VdcBllMessages, List<String>> v : violations.entrySet()) {
            String violationName = v.getKey().name();
            violationMessages.add(violationName);
            violationMessages.add(MessageFormat.format("${0}_LIST {1}",
                    violationName,
                    StringUtils.join(v.getValue(), ", ")));
        }

        return violationMessages;
    }

    /**
     * Add the given interface to the list of processed interfaces, failing if it already existed.
     *
     * @param iface
     *            The interface to add.
     * @return <code>true</code> if interface wasn't in the list and was added to it, otherwise <code>false</code>.
     */
    private boolean addInterfaceToProcessedList(VdsNetworkInterface iface) {
        if (ifaceNames.contains(iface.getName())) {
            addViolation(VdcBllMessages.NETWORK_INTERFACES_ALREADY_SPECIFIED, iface.getName());
            return false;
        }

        ifaceNames.add(iface.getName());
        return true;
    }

    /**
     * Detect a bond that it's slaves have changed, to add to the modified bonds list.<br>
     * Make sure not to add bond that was removed entirely.
     */
    private void detectSlaveChanges() {
        for (VdsNetworkInterface newIface : params.getInterfaces()) {
            VdsNetworkInterface existingIface = getExistingIfaces().get(newIface.getName());
            if (existingIface != null && !isBond(existingIface) && existingIface.getVlanId() == null) {
                String bondNameInNewIface = newIface.getBondName();
                String bondNameInOldIface = existingIface.getBondName();

                if (!StringUtils.equals(bondNameInNewIface, bondNameInOldIface)) {
                    if (bondNameInNewIface != null && !modifiedBonds.containsKey(bondNameInNewIface)) {
                        modifiedBonds.put(bondNameInNewIface, getExistingIfaces().get(bondNameInNewIface));
                    }

                    if (bondNameInOldIface != null && !modifiedBonds.containsKey(bondNameInNewIface)
                            && !removedBonds.contains(bondNameInOldIface)) {
                        modifiedBonds.put(bondNameInOldIface, getExistingIfaces().get(bondNameInOldIface));
                    }
                }
            }
        }
    }

    private Map<String, Network> getExistingClusterNetworks() {
        if (existingClusterNetworks == null) {
            existingClusterNetworks = Entities.entitiesByName(
                    getDbFacade().getNetworkDAO().getAllForCluster(vdsGroupId));
        }

        return existingClusterNetworks;
    }

    private Map<String, VdsNetworkInterface> getExistingIfaces() {
        if (existingIfaces == null) {
            List<VdsNetworkInterface> ifaces =
                    getDbFacade().getInterfaceDAO().getAllInterfacesForVds(params.getVdsId());

            for (VdsNetworkInterface iface : ifaces) {
                iface.setNetworkImplementationDetails(
                        NetworkUtils.calculateNetworkImplementationDetails(getExistingClusterNetworks(), iface));
            }

            existingIfaces = Entities.entitiesByName(ifaces);
        }

        return existingIfaces;
    }

    private VdsNetworkInterface getExistingIfaceByNetwork(String network) {
        for (VdsNetworkInterface iface : getExistingIfaces().values()) {
            if (network.equals(iface.getNetworkName())) {
                return iface;
            }
        }

        return null;
    }

    protected DbFacade getDbFacade() {
        return DbFacade.getInstance();
    }

    /**
     * extracting a network is done by matching the desired network name with the network details from db on
     * clusterNetworksMap. The desired network is just a key and actual network configuration is taken from the db
     * entity.
     *
     * @param iface
     *            current iterated interface
     */
    private void extractNetwork(VdsNetworkInterface iface) {
        String networkName = iface.getNetworkName();

        // prevent attaching 2 interfaces to 1 network
        if (attachedNetworksNames.contains(networkName)) {
            addViolation(VdcBllMessages.NETWORKS_ALREADY_ATTACHED_TO_IFACES, networkName);
        } else {
            attachedNetworksNames.add(networkName);

            // check if network exists on cluster
            if (getExistingClusterNetworks().containsKey(networkName)) {
                VdsNetworkInterface existingIface = getExistingIfaces().get(iface.getName());
                if (existingIface != null && !networkName.equals(existingIface.getNetworkName())) {
                    existingIface = getExistingIfaceByNetwork(networkName);
                }

                if (existingIface != null && existingIface.getNetworkImplementationDetails() != null
                        && !existingIface.getNetworkImplementationDetails().isInSync()) {
                    if (networkShouldBeSynced(networkName)) {
                        modifiedNetworks.add(getExistingClusterNetworks().get(networkName));
                    } else if (networkWasModified(iface)) {
                        addViolation(VdcBllMessages.NETWORKS_NOT_IN_SYNC, networkName);
                    }
                } else if (networkWasModified(iface)) {
                    modifiedNetworks.add(getExistingClusterNetworks().get(networkName));
                }
            } else if (unmanagedNetworkChanged(iface)) {
                addViolation(VdcBllMessages.NETWORK_NOT_EXISTS_IN_CURRENT_CLUSTER);
            }
        }
    }

    /**
     * Checks if an unmanaged network changed.<br>
     * This can be either if there is no existing interface for this network, i.e. it is a unmanaged VLAN which was
     * moved to a different interface, or if the network name on the existing interface is not the same as it was
     * before.
     *
     * @param iface
     *            The interface on which the unmanaged network is now defined.
     * @return <code>true</code> if the network changed, or <code>false</code> otherwise.
     */
    private boolean unmanagedNetworkChanged(VdsNetworkInterface iface) {
        VdsNetworkInterface existingIface = getExistingIfaces().get(iface.getName());
        return existingIface == null || !iface.getNetworkName().equals(existingIface.getNetworkName());
    }

    /**
     * Check if the network parameters on the given interface were modified (or network was added).
     *
     * @param iface
     *            The interface to check.
     * @return <code>true</code> if the network parameters were changed, or the network wan't on the given interface.
     *         <code>false</code> if it existed and didn't change.
     */
    private boolean networkWasModified(VdsNetworkInterface iface) {
        VdsNetworkInterface existingIface = getExistingIfaces().get(iface.getName());

        if (existingIface == null) {
            return true;
        }

        return !ObjectUtils.equals(iface.getNetworkName(), existingIface.getNetworkName())
                || iface.getBootProtocol() != existingIface.getBootProtocol()
                || staticBootProtoPropertiesChanged(iface, existingIface);
    }

    /**
     * Check if network's logical configuration should be synchronized (as sent in parameters).
     *
     * @param network
     *            The network to check if synchronized.
     * @return <code>true</code> in case network should be sunchronized, <code>false</code> otherwise.
     */
    private boolean networkShouldBeSynced(String network) {
        return params.getNetworksToSync() != null && params.getNetworksToSync().contains(network);
    }

    /**
     * @param iface
     *            New interface definition.
     * @param existingIface
     *            Existing interface definition.
     * @return <code>true</code> if the boot protocol is static, and one of the properties has changed.
     *         <code>false</code> otherwise.
     */
    private boolean staticBootProtoPropertiesChanged(VdsNetworkInterface iface, VdsNetworkInterface existingIface) {
        return iface.getBootProtocol() == NetworkBootProtocol.StaticIp
                && (!ObjectUtils.equals(iface.getAddress(), existingIface.getAddress())
                        || !ObjectUtils.equals(iface.getGateway(), existingIface.getGateway())
                        || !ObjectUtils.equals(iface.getSubnet(), existingIface.getSubnet()));
    }

    private boolean isBond(VdsNetworkInterface iface) {
        return Boolean.TRUE.equals(iface.getBonded());
    }

    /**
     * build mapping of the bond name - > list of slaves. slaves are interfaces with a pointer to the master bond by
     * bondName.
     *
     * @param iface
     */
    private void extractBondSlave(VdsNetworkInterface iface) {
        List<VdsNetworkInterface> slaves = bonds.get(iface.getBondName());
        if (slaves == null) {
            slaves = new ArrayList<VdsNetworkInterface>();
            bonds.put(iface.getBondName(), slaves);
        }

        slaves.add(iface);
    }

    /**
     * Extract the bond to the modified bonds list if it was added or the bond interface config has changed.
     *
     * @param iface
     *            The interface of the bond.
     * @param bondName
     *            The bond name.
     */
    private void extractBondIfModified(VdsNetworkInterface iface, String bondName) {
        if (!bonds.containsKey(bondName)) {
            bonds.put(bondName, new ArrayList<VdsNetworkInterface>());
        }

        if (bondWasModified(iface)) {
            modifiedBonds.put(bondName, iface);
        }
    }

    /**
     * Check if the given bond was modified (or added).<br>
     * Currently changes that are recognized are if bonding options changed, or the bond was added.
     *
     * @param iface
     *            The bond to check.
     * @return <code>true</code> if the bond was changed, or is a new one. <code>false</code> if it existed and didn't
     *         change.
     */
    private boolean bondWasModified(VdsNetworkInterface iface) {
        VdsNetworkInterface existingIface = getExistingIfaces().get(iface.getName());

        if (existingIface == null) {
            return true;
        }

        return !ObjectUtils.equals(iface.getBondOptions(), existingIface.getBondOptions());
    }

    /**
     * Extract the bonds to be removed. If a bond was attached to slaves but it's not attached to anything then it
     * should be removed. Otherwise, no point in removing it: Either it is still a bond, or it isn't attached to any
     * slaves either way so no need to touch it.
     */
    private void extractRemovedBonds() {
        for (VdsNetworkInterface iface : getExistingIfaces().values()) {
            String bondName = iface.getBondName();
            if (StringUtils.isNotBlank(bondName) && !bonds.containsKey(bondName)) {
                removedBonds.add(bondName);
            }
        }
    }

    private boolean validateBondSlavesCount() {
        boolean returnValue = true;
        for (Map.Entry<String, List<VdsNetworkInterface>> bondEntry : bonds.entrySet()) {
            if (bondEntry.getValue().size() < 2) {
                returnValue = false;
                addViolation(VdcBllMessages.NETWORK_BOND_PARAMETERS_INVALID);
            }
        }

        return returnValue;
    }

    /**
     * Calculate the networks that should be removed - If the network was attached to a NIC and is no longer attached to
     * it, then it will be removed.
     */
    private void extractRemovedNetworks() {
        for (VdsNetworkInterface iface : getExistingIfaces().values()) {
            String net = iface.getNetworkName();
            if (StringUtils.isNotBlank(net) && !attachedNetworksNames.contains(net)) {
                removedNetworks.add(net);
            }
        }
    }

    public List<Network> getNetworks() {
        return modifiedNetworks;
    }

    public List<String> getRemoveNetworks() {
        return removedNetworks;
    }

    public List<VdsNetworkInterface> getBonds() {
        return new ArrayList<VdsNetworkInterface>(modifiedBonds.values());
    }

    public Set<String> getRemovedBonds() {
        return removedBonds;
    }
}
