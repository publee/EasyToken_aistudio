package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TokenDao {
    @Query("SELECT * FROM tokens ORDER BY addedAt DESC")
    fun getAllTokens(): Flow<List<TokenEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToken(token: TokenEntity)

    @Update
    suspend fun updateToken(token: TokenEntity)

    @Delete
    suspend fun deleteToken(token: TokenEntity)

    @Query("DELETE FROM tokens WHERE id = :id")
    suspend fun deleteTokenById(id: Int)
}
