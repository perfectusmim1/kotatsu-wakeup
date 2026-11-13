package org.koitharu.kotatsu.core.network

import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.EnumMap
import java.util.Locale

class DoHManager(
	cache: Cache,
	private val settings: AppSettings,
) : Dns {

	private val bootstrapClient = OkHttpClient.Builder().cache(cache).build()

	private val cachedDelegates = EnumMap<DoHProvider, Dns>(DoHProvider::class.java)

	override fun lookup(hostname: String): List<InetAddress> {
		if (shouldBypassDoH(hostname)) {
			return Dns.SYSTEM.lookup(hostname)
		}
		val activeProvider = settings.dnsOverHttps
		val providers = buildList {
			add(activeProvider)
			if (activeProvider != DoHProvider.NONE) {
				add(DoHProvider.NONE)
			}
			if (activeProvider != DoHProvider.CLOUDFLARE) {
				add(DoHProvider.CLOUDFLARE)
			}
			if (activeProvider != DoHProvider.GOOGLE) {
				add(DoHProvider.GOOGLE)
			}
		}
		var firstFailure: UnknownHostException? = null
		for (provider in providers) {
			try {
				val result = getDelegate(provider).lookup(hostname)
				if (result.isNotEmpty()) {
					return result
				}
				val emptyFailure = UnknownHostException("Empty DNS result for $hostname via $provider")
				if (firstFailure == null) {
					firstFailure = emptyFailure
				} else {
					firstFailure.addSuppressed(emptyFailure)
				}
			} catch (e: UnknownHostException) {
				if (firstFailure == null) {
					firstFailure = e
				} else {
					firstFailure.addSuppressed(e)
				}
			}
		}
		throw firstFailure ?: UnknownHostException(hostname)
	}

	private fun getDelegate(provider: DoHProvider): Dns = synchronized(cachedDelegates) {
		cachedDelegates.getOrPut(provider) { createDelegate(provider) }
	}

	private fun shouldBypassDoH(hostname: String): Boolean {
		val normalized = hostname.trim().trimEnd('.').lowercase(Locale.ROOT)
		if (normalized.isEmpty()) return true
		if (isIpLiteral(normalized)) return true
		if ('.' !in normalized) return true
		return LOCAL_SUFFIXES.any { suffix -> normalized.endsWith(suffix) }
	}

	private fun isIpLiteral(host: String): Boolean {
		val unbracketed = if (host.startsWith('[') && host.endsWith(']') && host.length > 2) {
			host.substring(1, host.length - 1)
		} else {
			host
		}
		val withoutZone = unbracketed.substringBefore('%')
		if (withoutZone.isEmpty()) return false
		if (':' in withoutZone) {
			return withoutZone.all { ch ->
				ch in '0'..'9' || ch in 'a'..'f' || ch == ':' || ch == '.'
			}
		}
		val parts = withoutZone.split('.')
		if (parts.size != 4) return false
		return parts.all { part ->
			val num = part.toIntOrNull()
			num != null && num in 0..255 && (part.length == 1 || !part.startsWith('0'))
		}
	}

	private fun createDelegate(provider: DoHProvider): Dns = when (provider) {
		DoHProvider.NONE -> Dns.SYSTEM
		DoHProvider.GOOGLE -> DnsOverHttps.Builder().client(bootstrapClient)
			.url("https://dns.google/dns-query".toHttpUrl())
			.resolvePrivateAddresses(true)
			.bootstrapDnsHosts(
				listOfNotNull(
					tryGetByIp("8.8.4.4"),
					tryGetByIp("8.8.8.8"),
					tryGetByIp("2001:4860:4860::8888"),
					tryGetByIp("2001:4860:4860::8844"),
				),
			).build()

		DoHProvider.CLOUDFLARE -> DnsOverHttps.Builder().client(bootstrapClient)
			.url("https://cloudflare-dns.com/dns-query".toHttpUrl())
			.resolvePrivateAddresses(true)
			.bootstrapDnsHosts(
				listOfNotNull(
					tryGetByIp("162.159.36.1"),
					tryGetByIp("162.159.46.1"),
					tryGetByIp("1.1.1.1"),
					tryGetByIp("1.0.0.1"),
					tryGetByIp("162.159.132.53"),
					tryGetByIp("2606:4700:4700::1111"),
					tryGetByIp("2606:4700:4700::1001"),
					tryGetByIp("2606:4700:4700::0064"),
					tryGetByIp("2606:4700:4700::6400"),
				),
			).build()

		DoHProvider.ADGUARD -> DnsOverHttps.Builder().client(bootstrapClient)
			.url("https://dns-unfiltered.adguard.com/dns-query".toHttpUrl())
			.resolvePrivateAddresses(true)
			.bootstrapDnsHosts(
				listOfNotNull(
					tryGetByIp("94.140.14.140"),
					tryGetByIp("94.140.14.141"),
					tryGetByIp("2a10:50c0::1:ff"),
					tryGetByIp("2a10:50c0::2:ff"),
				),
			).build()

		DoHProvider.ZERO_MS -> DnsOverHttps.Builder().client(bootstrapClient)
			.url("https://v.recipes/dns-query".toHttpUrl())
			.resolvePublicAddresses(true)
			.build()
	}

	private fun tryGetByIp(ip: String): InetAddress? = try {
		InetAddress.getByName(ip)
	} catch (e: UnknownHostException) {
		e.printStackTraceDebug()
		null
	}

	private companion object {
		val LOCAL_SUFFIXES = arrayOf(
			".local",
			".localhost",
			".localdomain",
			".lan",
			".home",
			".internal",
			".arpa",
		)
	}
}
