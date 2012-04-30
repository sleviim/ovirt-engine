package org.ovirt.engine.core.common.action;

import org.ovirt.engine.core.common.businessentities.NetworkBootProtocol;
import org.ovirt.engine.core.common.businessentities.VdsNetworkInterface;
import org.ovirt.engine.core.common.businessentities.network;
import org.ovirt.engine.core.compat.Guid;

public class AttachNetworkToVdsParameters extends VdsActionParameters {
    private static final long serialVersionUID = 5446434263733512827L;
    private boolean privateCheckConnectivity;

    public boolean getCheckConnectivity() {
        return privateCheckConnectivity;
    }

    public void setCheckConnectivity(boolean value) {
        privateCheckConnectivity = value;
    }

    private network privateNetwork;

    public network getNetwork() {
        return privateNetwork;
    }

    public void setNetwork(network value) {
        privateNetwork = value;
    }

    private String privateOldNetworkName;

    public String getOldNetworkName() {
        return privateOldNetworkName;
    }

    public void setOldNetworkName(String value) {
        privateOldNetworkName = value;
    }

    private VdsNetworkInterface privateInterface;

    public VdsNetworkInterface getInterface() {
        return privateInterface;
    }

    public void setInterface(VdsNetworkInterface value) {
        privateInterface = value;
    }

    private String privateAddress;

    public String getAddress() {
        return privateAddress;
    }

    public void setAddress(String value) {
        privateAddress = value;
    }

    private String privateSubnet;

    public String getSubnet() {
        return privateSubnet;
    }

    public void setSubnet(String value) {
        privateSubnet = value;
    }

    private String privateGateway;

    public String getGateway() {
        return privateGateway;
    }

    public void setGateway(String value) {
        privateGateway = value;
    }

    private String privateBondingOptions;

    public String getBondingOptions() {
        return privateBondingOptions;
    }

    public void setBondingOptions(String value) {
        privateBondingOptions = value;
    }

    private NetworkBootProtocol privateBootProtocol = NetworkBootProtocol.forValue(0);

    public NetworkBootProtocol getBootProtocol() {
        return privateBootProtocol;
    }

    public void setBootProtocol(NetworkBootProtocol value) {
        privateBootProtocol = value;
    }

    public AttachNetworkToVdsParameters(Guid vdsId, network net, VdsNetworkInterface iface) {
        super(vdsId);
        setCheckConnectivity(false);
        setNetwork(net);
        setInterface(iface);
    }

    public AttachNetworkToVdsParameters() {
    }
}
