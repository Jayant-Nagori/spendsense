package com.jayant.spendsense.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Keychain generic passwords, accessible only while unlocked and never migrated to another device. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class KeychainSecretStore(private val service: String) : SecretStore {

    private fun dict(vararg pairs: Pair<CFTypeRef?, CFTypeRef?>): CFDictionaryRef? {
        val d = CFDictionaryCreateMutable(null, pairs.size.toLong(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        pairs.forEach { (k, v) -> CFDictionaryAddValue(d, k, v) }
        return d
    }

    private fun cf(s: String): CFTypeRef? = CFBridgingRetain(NSString.create(string = s))

    override fun read(key: String): String? = memScoped {
        val q = dict(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to cf(service),
            kSecAttrAccount to cf(key),
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        val out = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(q, out.ptr)
        CFBridgingRelease(q)
        if (status != 0) return@memScoped null
        val data = CFBridgingRelease(out.value) as? NSData ?: return@memScoped null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override fun write(key: String, value: String?) {
        val del = dict(kSecClass to kSecClassGenericPassword, kSecAttrService to cf(service), kSecAttrAccount to cf(key))
        SecItemDelete(del)
        CFBridgingRelease(del)
        if (value.isNullOrEmpty()) return
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val add = dict(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to cf(service),
            kSecAttrAccount to cf(key),
            kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecValueData to CFBridgingRetain(data),
        )
        SecItemAdd(add, null)
        CFBridgingRelease(add)
    }

    companion object {
        /** Removes every generic password this app stored. */
        fun deleteAll() {
            val q = CFDictionaryCreateMutable(null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
            SecItemDelete(q)
            CFBridgingRelease(q)
        }
    }
}
