package com.voxli.network

import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object NetworkModule {

    private const val CACHE_SIZE = 10L * 1024L * 1024L // 10 MB

    fun provideOkHttpClient(cacheDir: File? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .header("Accept", "text/html,application/xml,application/atom+xml,*/*")
                    .build()
                chain.proceed(request)
            }

        if (cacheDir != null) {
            builder.cache(Cache(File(cacheDir, "http_cache"), CACHE_SIZE))
        }

        return builder.build()
    }

    fun provideDoHClient(cacheDir: File? = null): OkHttpClient {
        val bootstrapClient = provideOkHttpClient(cacheDir)

        val dns = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1")
            )
            .build()

        return bootstrapClient.newBuilder()
            .dns(dns)
            .build()
    }
}
