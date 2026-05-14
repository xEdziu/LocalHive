package dev.adrian.goral.localhivebackend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.validator.routines.InetAddressValidator;

public class IpAddressValidator implements ConstraintValidator<IpAddress, String> {

    private static final InetAddressValidator INET = InetAddressValidator.getInstance();

    private boolean allowIpv4;
    private boolean allowIpv6;

    @Override
    public void initialize(IpAddress constraintAnnotation) {
        this.allowIpv4 = constraintAnnotation.allowIpv4();
        this.allowIpv6 = constraintAnnotation.allowIpv6();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null/empty is delegated to @NotNull
        if (value == null) {
            return true;
        }

        String ip = value.trim();
        if (ip.isEmpty()) {
            return true;
        }

        // We don't want CIDR, zone-id or URI-brackets in "ipAddress" field
        if (ip.contains("/") || ip.contains("%") || ip.contains("[") || ip.contains("]")) {
            return false;
        }

        boolean valid4 = allowIpv4 && INET.isValidInet4Address(ip);
        boolean valid6 = allowIpv6 && INET.isValidInet6Address(ip);

        return valid4 || valid6;
    }
}