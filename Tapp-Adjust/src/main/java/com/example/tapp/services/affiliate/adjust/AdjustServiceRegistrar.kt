package com.example.tapp.services.affiliate.adjust

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.example.tapp.models.Affiliate
import com.example.tapp.services.affiliate.AffiliateServiceFactory

internal class AdjustServiceRegistrar : ContentProvider() {
    override fun onCreate(): Boolean {
        AffiliateServiceFactory.register(Affiliate.ADJUST) { dependencies ->
            AdjustAffiliateService(dependencies)
        }
        return true
    }

    // Unused ContentProvider methods
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}