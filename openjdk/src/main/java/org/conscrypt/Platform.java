/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.conscrypt;

import java.io.FileDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketImpl;
import java.nio.channels.SocketChannel;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import sun.security.x509.AlgorithmId;

/**
 * Platform-specific methods for OpenJDK.
 *
 * Uses reflection to implement Java 8 SSL features for backwards compatibility.
 */
final class Platform {
    private static final boolean JAVA8;
    private static final Method GET_CURVE_NAME_METHOD;
    private static final Method SET_USE_CIPHER_SUITES_ORDER_METHOD;
    private static final Method GET_USE_CIPHER_SUITES_ORDER_METHOD;
    private static final Method SET_SERVER_NAMES_METHOD;
    private static final Method GET_SERVER_NAMES_METHOD;
    private static final Method GET_TYPE_METHOD;
    private static final Method GET_ASCII_NAME_METHOD;
    private static final Constructor<?> SNI_HOST_NAME_CONSTRUCTOR;
    static {
        Method getCurveNameMethod = null;
        try {
            getCurveNameMethod = ECParameterSpec.class.getDeclaredMethod("getCurveName");
            getCurveNameMethod.setAccessible(true);
        } catch (Exception ignored) {
        }
        GET_CURVE_NAME_METHOD = getCurveNameMethod;

        // Java 8 methods.
        boolean java8 = false;
        Method setUseCipherSuitesOrderMethod = null;
        Method getUseCipherSuitesOrderMethod = null;
        Method setServerNamesMethod = null;
        Method getServerNamesMethod = null;
        Method getTypeMethod = null;
        Method getAsciiNameMethod = null;
        Constructor<?> sniHostNameConstructor = null;
        try {
            setUseCipherSuitesOrderMethod =
                SSLParameters.class.getMethod("setUseCipherSuitesOrder", boolean.class);
            getUseCipherSuitesOrderMethod = SSLParameters.class.getMethod("getUseCipherSuitesOrder");
            setServerNamesMethod = SSLParameters.class.getMethod("setServerNames", List.class);
            getServerNamesMethod = SSLParameters.class.getMethod("getServerNames");
            Class<?> sniHostNameClass = Class.forName("javax.net.ssl.SNIHostName");
            getTypeMethod = sniHostNameClass.getMethod("getType");
            getAsciiNameMethod = sniHostNameClass.getMethod("getAsciiName");
            sniHostNameConstructor = sniHostNameClass.getConstructor(String.class);
            java8 = true;
        } catch (Exception ignored) {
            // Ignored.
        }
        SET_USE_CIPHER_SUITES_ORDER_METHOD = java8 ? setUseCipherSuitesOrderMethod : null;
        GET_USE_CIPHER_SUITES_ORDER_METHOD = java8 ? getUseCipherSuitesOrderMethod : null;
        SET_SERVER_NAMES_METHOD = java8 ? setServerNamesMethod : null;
        GET_SERVER_NAMES_METHOD = java8 ? getServerNamesMethod : null;
        GET_TYPE_METHOD = java8 ? getTypeMethod : null;
        GET_ASCII_NAME_METHOD = java8 ? getAsciiNameMethod : null;
        SNI_HOST_NAME_CONSTRUCTOR = java8 ? sniHostNameConstructor : null;
        JAVA8 = java8;
    }

    private Platform() {}

    static void setup() {}

    static FileDescriptor getFileDescriptor(Socket s) {
        try {
            SocketChannel channel = s.getChannel();
            if (channel != null) {
                Field f_fd = channel.getClass().getDeclaredField("fd");
                f_fd.setAccessible(true);
                return (FileDescriptor) f_fd.get(channel);
            }
        } catch (Exception e) {
            // Try socket class below...
        }

        try {
            Field f_impl = Socket.class.getDeclaredField("impl");
            f_impl.setAccessible(true);
            Object socketImpl = f_impl.get(s);
            Field f_fd = SocketImpl.class.getDeclaredField("fd");
            f_fd.setAccessible(true);
            return (FileDescriptor) f_fd.get(socketImpl);
        } catch (Exception e) {
            throw new RuntimeException("Can't get FileDescriptor from socket", e);
        }
    }

    static FileDescriptor getFileDescriptorFromSSLSocket(AbstractConscryptSocket socket) {
        return getFileDescriptor(socket);
    }

    static String getCurveName(ECParameterSpec spec) {
        if (GET_CURVE_NAME_METHOD != null) {
            try {
                return (String) GET_CURVE_NAME_METHOD.invoke(spec);
            } catch (Exception ignored) {
                // Ignored
            }
        }
        return null;
    }

    static void setCurveName(@SuppressWarnings("unused") ECParameterSpec spec,
            @SuppressWarnings("unused") String curveName) {
        // This doesn't appear to be needed.
    }

    /*
     * Call Os.setsockoptTimeval via reflection.
     */
    static void setSocketWriteTimeout(@SuppressWarnings("unused") Socket s,
            @SuppressWarnings("unused") long timeoutMillis) throws SocketException {
        // TODO: figure this out on the RI
    }

    @SuppressWarnings("unchecked")
    public static void setSSLParameters(
            SSLParameters params, SSLParametersImpl impl, AbstractConscryptSocket socket) {
        impl.setEndpointIdentificationAlgorithm(params.getEndpointIdentificationAlgorithm());
        if (JAVA8) {
            try {
                impl.setUseCipherSuitesOrder(
                    (boolean) GET_USE_CIPHER_SUITES_ORDER_METHOD.invoke(params));
                List<Object> serverNames = (List<Object>) GET_SERVER_NAMES_METHOD.invoke(params);

                // javax.net.ssl.StandardConstants.SNI_HOST_NAME
                int hostNameType = 0;
                if (serverNames != null) {
                    for (Object serverName : serverNames) {
                        if ((int) GET_TYPE_METHOD.invoke(serverName) == hostNameType) {
                            socket.setHostname((String) GET_ASCII_NAME_METHOD.invoke(serverName));
                            break;
                        }
                    }
                }
            } catch (IllegalAccessException ignored) {
            } catch (InvocationTargetException ignored) {
            }
        }
    }

    @SuppressWarnings({"LiteralClassName", "rawtypes"})
    public static void getSSLParameters(
            SSLParameters params, SSLParametersImpl impl, AbstractConscryptSocket socket) {
        params.setEndpointIdentificationAlgorithm(impl.getEndpointIdentificationAlgorithm());
        if (JAVA8) {
            try {
                SET_USE_CIPHER_SUITES_ORDER_METHOD.invoke(params, impl.getUseCipherSuitesOrder());
                if (impl.getUseSni() && AddressUtils.isValidSniHostname(socket.getHostname())) {
                    SET_SERVER_NAMES_METHOD.invoke(params,
                        (Collections.singletonList(
                            SNI_HOST_NAME_CONSTRUCTOR.newInstance(socket.getHostname()))));
                }
            } catch (IllegalAccessException ignored) {
            } catch (InvocationTargetException ignored) {
            } catch (InstantiationException ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void setSSLParameters(
            SSLParameters params, SSLParametersImpl impl, ConscryptEngine engine) {
        impl.setEndpointIdentificationAlgorithm(params.getEndpointIdentificationAlgorithm());
        if (JAVA8) {
            try {
                impl.setUseCipherSuitesOrder(
                    (boolean) GET_USE_CIPHER_SUITES_ORDER_METHOD.invoke(params));
                List<Object> serverNames = (List<Object>) GET_SERVER_NAMES_METHOD.invoke(params);

                int hostNameType = 0;
                if (serverNames != null) {
                    for (Object serverName : serverNames) {
                        if ((int) GET_TYPE_METHOD.invoke(serverName) == hostNameType) {
                            engine.setHostname((String) GET_ASCII_NAME_METHOD.invoke(serverName));
                            break;
                        }
                    }
                }
            } catch (IllegalAccessException ignored) {
            } catch (InvocationTargetException ignored) {
            }
        }
    }

    @SuppressWarnings({"LiteralClassName", "rawtypes"})
    public static void getSSLParameters(
            SSLParameters params, SSLParametersImpl impl, ConscryptEngine engine) {
        params.setEndpointIdentificationAlgorithm(impl.getEndpointIdentificationAlgorithm());
        if (JAVA8) {
            try {
                SET_USE_CIPHER_SUITES_ORDER_METHOD.invoke(params, impl.getUseCipherSuitesOrder());
                if (impl.getUseSni() && AddressUtils.isValidSniHostname(engine.getHostname())) {
                    SET_SERVER_NAMES_METHOD.invoke(params,
                        (Collections.singletonList(
                            SNI_HOST_NAME_CONSTRUCTOR.newInstance(engine.getHostname()))));
                }
            } catch (IllegalAccessException ignored) {
            } catch (InvocationTargetException ignored) {
            } catch (InstantiationException ignored) {
            }
        }
    }

    @SuppressWarnings("unused")
    static void setEndpointIdentificationAlgorithm(
            SSLParameters params, String endpointIdentificationAlgorithm) {
        params.setEndpointIdentificationAlgorithm(endpointIdentificationAlgorithm);
    }

    @SuppressWarnings("unused")
    static String getEndpointIdentificationAlgorithm(SSLParameters params) {
        return params.getEndpointIdentificationAlgorithm();
    }

    static void checkClientTrusted(X509TrustManager tm, X509Certificate[] chain, String authType,
            AbstractConscryptSocket socket) throws CertificateException {
        if (tm instanceof X509ExtendedTrustManager) {
            X509ExtendedTrustManager x509etm = (X509ExtendedTrustManager) tm;
            x509etm.checkClientTrusted(chain, authType, socket);
        } else {
            tm.checkClientTrusted(chain, authType);
        }
    }

    static void checkServerTrusted(X509TrustManager tm, X509Certificate[] chain, String authType,
            AbstractConscryptSocket socket) throws CertificateException {
        if (tm instanceof X509ExtendedTrustManager) {
            X509ExtendedTrustManager x509etm = (X509ExtendedTrustManager) tm;
            x509etm.checkServerTrusted(chain, authType, socket);
        } else {
            tm.checkServerTrusted(chain, authType);
        }
    }

    static void checkClientTrusted(X509TrustManager tm, X509Certificate[] chain, String authType,
            ConscryptEngine engine) throws CertificateException {
        if (tm instanceof X509ExtendedTrustManager) {
            X509ExtendedTrustManager x509etm = (X509ExtendedTrustManager) tm;
            x509etm.checkClientTrusted(chain, authType, engine);
        } else {
            tm.checkClientTrusted(chain, authType);
        }
    }

    static void checkServerTrusted(X509TrustManager tm, X509Certificate[] chain, String authType,
            ConscryptEngine engine) throws CertificateException {
        if (tm instanceof X509ExtendedTrustManager) {
            X509ExtendedTrustManager x509etm = (X509ExtendedTrustManager) tm;
            x509etm.checkServerTrusted(chain, authType, engine);
        } else {
            tm.checkServerTrusted(chain, authType);
        }
    }

    /**
     * Wraps an old AndroidOpenSSL key instance. This is not needed on RI.
     */
    static OpenSSLKey wrapRsaKey(@SuppressWarnings("unused") PrivateKey javaKey) {
        return null;
    }

    /**
     * Logs to the system EventLog system.
     */
    static void logEvent(@SuppressWarnings("unused") String message) {}

    /**
     * Returns true if the supplied hostname is an literal IP address.
     */
    static boolean isLiteralIpAddress(String hostname) {
        // TODO: any RI API to make this better?
        return AddressUtils.isLiteralIpAddress(hostname);
    }

    /**
     * For unbundled versions, SNI is always enabled by default.
     */
    @SuppressWarnings("unused")
    static boolean isSniEnabledByDefault() {
        return true;
    }

    /**
     * Currently we don't wrap anything from the RI.
     */
    static SSLSocketFactory wrapSocketFactoryIfNeeded(OpenSSLSocketFactoryImpl factory) {
        return factory;
    }

    /**
     * Convert from platform's GCMParameterSpec to our internal version.
     */
    static GCMParameters fromGCMParameterSpec(AlgorithmParameterSpec params) {
        if (params instanceof GCMParameterSpec) {
            GCMParameterSpec gcmParams = (GCMParameterSpec) params;
            return new GCMParameters(gcmParams.getTLen(), gcmParams.getIV());
        }
        return null;
    }

    /**
     * Creates a platform version of {@code GCMParameterSpec}.
     */
    static AlgorithmParameterSpec toGCMParameterSpec(int tagLenInBits, byte[] iv) {
        return new GCMParameterSpec(tagLenInBits, iv);
    }

    /*
     * CloseGuard functions.
     */

    static Object closeGuardGet() {
        return null;
    }

    static void closeGuardOpen(@SuppressWarnings("unused") Object guardObj,
            @SuppressWarnings("unused") String message) {}

    static void closeGuardClose(@SuppressWarnings("unused") Object guardObj) {}

    static void closeGuardWarnIfOpen(@SuppressWarnings("unused") Object guardObj) {}

    /*
     * BlockGuard functions.
     */

    static void blockGuardOnNetwork() {}

    /**
     * OID to Algorithm Name mapping.
     */
    static String oidToAlgorithmName(String oid) {
        try {
            return AlgorithmId.get(oid).getName();
        } catch (NoSuchAlgorithmException e) {
            return oid;
        }
    }

    /*
     * Pre-Java-8 backward compatibility.
     */

    static SSLSession wrapSSLSession(ActiveSession sslSession) {
        return new DelegatingExtendedSSLSession(sslSession);
    }

    @SuppressWarnings("unused")
    static SSLSession unwrapSSLSession(SSLSession sslSession) {
        if (sslSession instanceof DelegatingExtendedSSLSession) {
            return ((DelegatingExtendedSSLSession) sslSession).getDelegate();
        }

        return sslSession;
    }

    public static String getOriginalHostNameFromInetAddress(InetAddress addr) {
        try {
            Method getHolder = InetAddress.class.getDeclaredMethod("holder");
            getHolder.setAccessible(true);

            Method getOriginalHostName = Class.forName("java.net.InetAddress$InetAddressHolder")
                                                 .getDeclaredMethod("getOriginalHostName");
            getOriginalHostName.setAccessible(true);

            String originalHostName = (String) getOriginalHostName.invoke(getHolder.invoke(addr));
            if (originalHostName == null) {
                return addr.getHostAddress();
            }
            return originalHostName;
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Failed to get originalHostName", e);
        } catch (ReflectiveOperationException ignore) {
            // passthrough and return addr.getHostAddress()
        }
        return addr.getHostAddress();
    }

    /*
     * Pre-Java-7 backward compatibility.
     */

    static String getHostStringFromInetSocketAddress(InetSocketAddress addr) {
        return addr.getHostString();
    }

    /**
     * Check if SCT verification is required for a given hostname.
     *
     * SCT Verification is enabled using {@code Security} properties.
     * The "conscrypt.ct.enable" property must be true, as well as a per domain property.
     * The reverse notation of the domain name, prefixed with "conscrypt.ct.enforce."
     * is used as the property name.
     * Basic globbing is also supported.
     *
     * For example, for the domain foo.bar.com, the following properties will be
     * looked up, in order of precedence.
     * - conscrypt.ct.enforce.com.bar.foo
     * - conscrypt.ct.enforce.com.bar.*
     * - conscrypt.ct.enforce.com.*
     * - conscrypt.ct.enforce.*
     */
    static boolean isCTVerificationRequired(String hostname) {
        if (hostname == null) {
            return false;
        }

        String property = Security.getProperty("conscrypt.ct.enable");
        if (property == null || !Boolean.valueOf(property.toLowerCase())) {
            return false;
        }

        List<String> parts = Arrays.asList(hostname.split("\\."));
        Collections.reverse(parts);

        boolean enable = false;
        StringBuilder propertyName = new StringBuilder("conscrypt.ct.enforce");
        // The loop keeps going on even once we've found a match
        // This allows for finer grained settings on subdomains
        for (String part : parts) {
            property = Security.getProperty(propertyName + ".*");
            if (property != null) {
                enable = Boolean.valueOf(property.toLowerCase());
            }

            propertyName.append(".").append(part);
        }

        property = Security.getProperty(propertyName.toString());
        if (property != null) {
            enable = Boolean.valueOf(property.toLowerCase());
        }
        return enable;
    }
}
