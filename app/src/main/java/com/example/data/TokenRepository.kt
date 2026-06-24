package com.example.data

import kotlinx.coroutines.flow.Flow

class TokenRepository(private val tokenDao: TokenDao) {
    val allTokens: Flow<List<TokenEntity>> = tokenDao.getAllTokens()

    suspend fun insert(token: TokenEntity) {
        tokenDao.insertToken(token)
    }

    suspend fun update(token: TokenEntity) {
        tokenDao.updateToken(token)
    }

    suspend fun delete(token: TokenEntity) {
        tokenDao.deleteToken(token)
    }

    suspend fun deleteById(id: Int) {
        tokenDao.deleteTokenById(id)
    }
}
