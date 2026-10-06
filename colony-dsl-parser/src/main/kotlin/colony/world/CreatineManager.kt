package colony.world

import kotlinx.serialization.Serializable


data class CreatineDepositResult(
    val totalMined: Double,
    val unitsForSale: Double,
    val unitsForStock: Double,
    val revenue: Long
)

/**
 * Manages the settlement's Creatine lifecycle:
 * Mining at the mine -> Storage at Depository/Medical Center -> Commercial sale & Patient healing.
 */
class CreatineManager(
    val config: CreatineEconomyConfig = CreatineEconomyConfig()
) {
    var mineStock: Double = 0.0
    var storedStock: Double = config.initialDepotStock
    var medicalCenterStock: Double = config.initialMedicalStock

    var stock: Double
        get() = storedStock + medicalCenterStock
        set(value) {
            storedStock = value / 2.0
            medicalCenterStock = value / 2.0
        }

    var totalMined: Double = 0.0
        private set
    var totalSold: Double = 0.0
        private set
    var totalRevenue: Long = 0L
        private set
    var totalHealedCount: Long = 0L
        private set
    var pendingSalesRevenue: Long = 0L
        private set
    private var fractionalRevenue: Double = 0.0
    var lastActiveMinersCount: Int = 0

    /**
     * Raw extraction at the mine pit.
     */
    fun produceAtMine(amount: Double): Double {
        if (!amount.isFinite() || amount <= 0.0) return 0.0
        mineStock += amount
        totalMined += amount
        return amount
    }

    /**
     * Unload hauled creatine at the Depository (Склад).
     * Splits into commercial export sales and stored stockpile.
     */
    fun transferToDepository(amount: Double): CreatineDepositResult {
        val actual = if (amount.isFinite()) amount.coerceAtLeast(0.0) else 0.0
        val forSale = actual * config.sellFraction
        val forStock = actual - forSale

        storedStock += forStock
        totalSold += forSale

        val exactRevenue = forSale * config.pricePerUnit + fractionalRevenue
        val revenue = exactRevenue.toLong()
        fractionalRevenue = exactRevenue - revenue
        totalRevenue += revenue
        pendingSalesRevenue += revenue

        return CreatineDepositResult(
            totalMined = actual,
            unitsForSale = forSale,
            unitsForStock = forStock,
            revenue = revenue
        )
    }

    /**
     * Supply transport from Depository to Medical Center (Медпункт).
     */
    /**
     * Direct deposit (for testing / fallback).
     */
    fun deposit(amount: Double): CreatineDepositResult {
        if (!amount.isFinite() || amount <= 0.0) return CreatineDepositResult(0.0, 0.0, 0.0, 0L)
        val mined = produceAtMine(amount)
        mineStock = (mineStock - mined).coerceAtLeast(0.0)
        return transferToDepository(mined)
    }

    /**
     * Attempts to heal a patient using available creatine stock at the Medical Center.
     * Returns true if treated successfully.
     */
    fun tryHeal(currentHealth: Double): Boolean {
        if (currentHealth >= 100.0) return false
        val available = if (medicalCenterStock >= config.healCost) {
            medicalCenterStock -= config.healCost
            true
        } else {
            false
        }
        if (!available) return false

        totalHealedCount++
        return true
    }

    /**
     * Flushes accumulated commercial revenue into the world ledger.
     */
    fun flushRevenue(): Long {
        val rev = pendingSalesRevenue
        pendingSalesRevenue = 0L
        return rev
    }
}
