package com.myway.app.ui.common

import com.myway.app.data.local.SavedStation
import com.myway.app.ui.search.StationGroupItem

fun StationGroupItem.toSaved() = SavedStation(name, stations)
fun SavedStation.toItem(isFavorite: Boolean = false) = StationGroupItem(name, stations, isFavorite)
