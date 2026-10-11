package com.example.eqify

sealed interface HeadphoneCorrectionSelection {
    data class Dataset(val name: String) : HeadphoneCorrectionSelection
    data class Custom(val profileId: String) : HeadphoneCorrectionSelection
    data object None : HeadphoneCorrectionSelection
}
