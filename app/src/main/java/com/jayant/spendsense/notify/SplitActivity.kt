package com.jayant.spendsense.notify

import androidx.fragment.app.FragmentActivity

// OWNER: UI agent. Dialog-style activity opened from the split notification. Extra: EXTRA_TXN_ID (Long).
class SplitActivity : FragmentActivity() {
    companion object { const val EXTRA_TXN_ID = "txn_id" }
}
