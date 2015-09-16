package com.emc.storageos.services.util;

import java.net.InetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.net.InetAddresses;

public class SysUtils {
    private static final Logger log = LoggerFactory.getLogger(SysUtils.class);
    
    public boolean checkIfBehindNat(String ipv4Str, String ipv6Str, String clientIp) throws Exception {
        log.info(String.format("Performing NAT check, client address connecting to VIP: %s. Client reports its IPv4 = %s, IPv6 = %s",
                clientIp, ipv4Str, ipv6Str));

        InetAddress ipv4Addr = parseInetAddress(ipv4Str);
        InetAddress ipv6Addr = parseInetAddress(ipv6Str);
        InetAddress directAddr = parseInetAddress(clientIp);
        if (directAddr == null || ipv4Addr == null && ipv6Addr == null) {
            String ipAddrsStr = Strings.join("|", ipv4Str, ipv6Str);
            log.error("checkParam is {}, X-Forwarded-For is {}", ipAddrsStr, clientIp);
            throw new Exception(ipAddrsStr);
        }

        return !directAddr.equals(ipv4Addr) && !directAddr.equals(ipv6Addr);
    }
    
    protected InetAddress parseInetAddress(String addrStr) {
        if (addrStr == null || addrStr.isEmpty()) {
            return null;
        }

        try {
            return InetAddresses.forString(addrStr);
        } catch (IllegalArgumentException e) {
            log.error(String.format("Failed to parse Inet address string: %s", addrStr), e);
            return null;
        }
    }
}
