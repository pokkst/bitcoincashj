package org.bitcoinj.core

import io.reactivex.Single
import org.bitcoinj.kits.SlpAppKit
import org.bitcoinj.wallet.SendRequest
import org.spongycastle.crypto.params.KeyParameter
import java.math.BigDecimal

class SlpTxBuilder {
    companion object {
        @JvmStatic
        fun buildTx(tokenId: String, amount: Double, toAddress: String, slpAppKit: SlpAppKit, aesKey: KeyParameter): Single<Transaction> {
            return sendTokenUtxoSelection(tokenId, amount, slpAppKit)
                    .map {
                        // Add OP RETURN and receiver output
                        val req = SendRequest.createSlpTransaction(slpAppKit.wallet.params)
                        req.aesKey = aesKey
                        req.shuffleOutputs = false
                        req.utxos = null
                        req.feePerKb = Coin.valueOf(1000L)

                        val opReturn = if (it.quantities.size == 1) {
                            SlpOpReturnOutputSend(it.tokenId, it.quantities[0].toLong(), 0)
                        } else {
                            SlpOpReturnOutputSend(it.tokenId, it.quantities[0].toLong(), it.quantities[1].toLong())
                        }

                        req.tx.addOutput(Coin.ZERO, opReturn.script)
                        req.tx.addOutput(slpAppKit.wallet.params.minNonDustOutput, Address.fromCashAddr(slpAppKit.wallet.params, toAddress))

                        // Send our token change back to our SLP address
                        if (it.quantities.size == 2) {
                            req.tx.addOutput(Coin.valueOf(DUST_LIMIT), Address.fromCashAddr(slpAppKit.wallet.params, slpAppKit.freshSlpReceiveAddress().toCashAddress()))
                        }

                        // Send our BCH change back to our BCH address
                        if (it.changeSatoshi >= DUST_LIMIT) {
                            req.tx.addOutput(Coin.valueOf(it.changeSatoshi), slpAppKit.wallet.freshReceiveAddress())
                        }

                        it.selectedUtxos.forEach { req.tx.addInput(it) }

                        val tx = slpAppKit.wallet.sendCoinsOffline(req)
                        tx
                    }
        }

        private val OP_RETURN_NUM_BYTES_BASE: Int = 55
        private val QUANTITY_NUM_BYTES: Int = 9
        val maxRawAmount = BigDecimal(ULong.MAX_VALUE.toString())
        const val DUST_LIMIT: Long = 546

        fun sendTokenUtxoSelection(tokenId: String, numTokens: Double, slpAppKit: SlpAppKit): Single<SendTokenUtxoSelection> {
            return Single.fromCallable { // Wrap for now to protect against blocking non reactive calls
                val tokenDetails: SlpToken = slpAppKit.getSlpToken(tokenId)
                val sendTokensRaw =  toRawAmount(numTokens.toBigDecimal(), slpAppKit.getSlpToken(tokenId))
                var sendSatoshi = DUST_LIMIT // At least one dust limit output to the token receiver

                val utxos = slpAppKit.wallet.utxos

                // First select enough token utxo's and just take what we get in terms of BCH
                var inputTokensRaw = ULong.MIN_VALUE
                var inputSatoshi = 0L
                val selectedUtxos = slpAppKit.slpUtxos
                        .filter { it.tokenId == tokenId }
                        .sortedBy { it.tokenAmount }
                        .takeWhile {
                            val amountTooLow = inputTokensRaw < sendTokensRaw
                            if (amountTooLow) {
                                inputTokensRaw += it.tokenAmount.toLong().toULong()
                                inputSatoshi += (it.txUtxo.value.value - 148) // Deduct input fee
                            }
                            amountTooLow
                        }
                        .map { it.txUtxo }
                        .toMutableList()
                if (inputTokensRaw < sendTokensRaw) {
                    throw RuntimeException("insufficient token balance=$inputTokensRaw")
                } else if (inputTokensRaw > sendTokensRaw) {
                    // If there's token change we need at least another dust limit worth of BCH
                    sendSatoshi += DUST_LIMIT
                }

                val propagationExtraFee = 50 // When too close 1sat/byte tx's don't propagate well
                val numOutputs = 3 // Assume three outputs in addition to the op return.
                val numQuanitites = 2 // Assume one token receiver and the token receiver
                val fee = outputFee(numOutputs) + sizeInBytes(numQuanitites) + propagationExtraFee

                // If we can not yet afford the fee + dust limit to send, use pure BCH utxo's
                selectedUtxos.addAll(utxos
                        .sortedBy { it.value.value }
                        .takeWhile {
                            val amountTooLow = inputSatoshi <= (sendSatoshi + fee)
                            if (amountTooLow) {
                                inputSatoshi += (it.value.value - 148) // Deduct input fee
                            }
                            amountTooLow
                        })

                val changeSatoshi = inputSatoshi - sendSatoshi - fee
                if (changeSatoshi < 0) {
                    throw IllegalArgumentException("Insufficient BCH balance=$inputSatoshi required $sendSatoshi + fees")
                }

                // We have enough tokens and BCH. Create the transaction
                val quantities = mutableListOf(sendTokensRaw)
                val changeTokens = inputTokensRaw - sendTokensRaw
                if (changeTokens > 0u) {
                    quantities.add(changeTokens)
                }

                SendTokenUtxoSelection(tokenId, quantities, changeSatoshi, selectedUtxos)
            }
        }

        fun toRawAmount(amount: BigDecimal, slpToken: SlpToken): ULong {
            if (amount > maxRawAmount) {
                throw IllegalArgumentException("amount larger than 8 unsigned bytes")
            } else if (amount.scale() > slpToken.decimals) {
                throw IllegalArgumentException("${slpToken.ticker} supports maximum ${slpToken.decimals} decimals but amount is $amount")
            }
            return amount.scaleByPowerOfTen(slpToken.decimals).toLong().toULong()
        }

        fun outputFee(numOutputs: Int): Long {
            return numOutputs.toLong() * 34
        }

        fun sizeInBytes(numQuantities: Int): Int {
            return OP_RETURN_NUM_BYTES_BASE + numQuantities * QUANTITY_NUM_BYTES
        }

        data class SendTokenUtxoSelection(
                val tokenId: String, val quantities: List<ULong>, val changeSatoshi: Long,
                val selectedUtxos: List<TransactionOutput>
        )
    }
}