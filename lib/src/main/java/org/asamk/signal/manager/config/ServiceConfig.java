package org.asamk.signal.manager.config;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import org.asamk.signal.manager.api.ServiceEnvironment;
import org.asamk.signal.manager.config.FixHttpLoggingInterceptor;
import org.signal.libsignal.protocol.util.Medium;
import org.whispersystems.signalservice.api.account.AccountAttributes;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;


import okhttp3.Interceptor;

import okhttp3.logging.HttpLoggingInterceptor;

public class ServiceConfig {

    public static final int PREKEY_MINIMUM_COUNT = 10;
    public static final int PREKEY_BATCH_SIZE = 100;
    public static final int PREKEY_MAXIMUM_ID = Medium.MAX_VALUE;
    public static final long PREKEY_ARCHIVE_AGE = TimeUnit.DAYS.toMillis(30);
    public static final long PREKEY_STALE_AGE = TimeUnit.DAYS.toMillis(90);
    public static final long SIGNED_PREKEY_ROTATE_AGE = TimeUnit.DAYS.toMillis(2);

    public static final int MAX_ATTACHMENT_SIZE = 150 * 1024 * 1024;
    public static final long MAX_ENVELOPE_SIZE = 0;
    public static final long AVATAR_DOWNLOAD_FAILSAFE_MAX_SIZE = 10 * 1024 * 1024;
    public static final boolean AUTOMATIC_NETWORK_RETRY = true;
    public static final int GROUP_MAX_SIZE = 1001;
    public static final int MAXIMUM_ONE_OFF_REQUEST_SIZE = 3;

    public static AccountAttributes.Capabilities getCapabilities(boolean isPrimaryDevice) {
        final var giftBadges = !isPrimaryDevice;
        final var pni = !isPrimaryDevice;
        final var paymentActivation = !isPrimaryDevice;
        return new AccountAttributes.Capabilities(false, true, true, true, true, giftBadges, pni, paymentActivation);
    }

    public static ServiceEnvironmentConfig getServiceEnvironmentConfig(
            ServiceEnvironment serviceEnvironment, String userAgent
    ) {
        final Interceptor userAgentInterceptor = chain -> chain.proceed(chain.request()
                .newBuilder()
                .header("User-Agent", userAgent)
                .build());
        final Interceptor closeConnectionInterceptor = chain -> chain.proceed(chain.request()
                .newBuilder()
                .addHeader("Connection", "close")

                .build());

        final Logger logger = LoggerFactory.getLogger(ServiceConfig.class);
        HttpLoggingInterceptor httpLoggingInterceptor = new HttpLoggingInterceptor();

        httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        logger.warn("activated HTTP logging");
//        final var interceptors = List.of(userAgentInterceptor);
        final var interceptors = List.of(userAgentInterceptor, new FixHttpLoggingInterceptor(), httpLoggingInterceptor);

        return switch (serviceEnvironment) {
            case LIVE -> new ServiceEnvironmentConfig(serviceEnvironment,
                    LiveConfig.createDefaultServiceConfiguration(interceptors),
                    LiveConfig.getUnidentifiedSenderTrustRoot(),
                    LiveConfig.getCdsiMrenclave(),
                    LiveConfig.getSvr2Mrenclave());
            case STAGING -> new ServiceEnvironmentConfig(serviceEnvironment,
                    StagingConfig.createDefaultServiceConfiguration(interceptors),
                    StagingConfig.getUnidentifiedSenderTrustRoot(),
                    StagingConfig.getCdsiMrenclave(),
                    StagingConfig.getSvr2Mrenclave());
        };
    }
}
