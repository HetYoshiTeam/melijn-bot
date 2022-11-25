package me.melijn.bot.database.model

import me.melijn.apredgres.cacheable.Cacheable
import me.melijn.apredgres.createtable.CreateTable
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

@CreateTable
@Cacheable
object PlaylistTrack : TrackJoinTable("playlist_track") {

    val playlistId = reference("playlist", Playlist.playlistId)
    val addedTime = datetime("added_time")
    override val trackId = uuid("track_id").references(Track.trackId)

    override val primaryKey: PrimaryKey = PrimaryKey(trackId)

    init {
        index(true, playlistId, trackId)
    }
}