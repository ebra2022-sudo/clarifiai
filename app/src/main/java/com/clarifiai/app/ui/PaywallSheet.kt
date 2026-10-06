package com.clarifiai.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clarifiai.app.billing.PlanOffer
import com.clarifiai.app.data.Tier

private data class PlanInfo(val tier: Tier, val tagline: String, val features: List<String>)

// Keep in sync with backend services/tiers.py
private val PLANS = listOf(
    PlanInfo(Tier.FREE, "Try it on one project", listOf(
        "3 audits / month", "Today & last 3 days", "Top 3 friction pages",
        "Hotfixes + friction trends (roadmap locked)", "1 project")),
    PlanInfo(Tier.PRO, "For product teams", listOf(
        "60 audits / month", "Up to last 7 days", "Top 10 friction pages",
        "Full strategic sprint roadmap", "Executive PDF export & share", "5 projects")),
    PlanInfo(Tier.MAX, "For agencies & orgs", listOf(
        "600 audits / month", "Last 30 days + custom ranges (90 days)", "Device-level breakdown",
        "White-label PDF reports", "Everything in Pro", "25 projects")),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallSheet(
    currentTier: Tier,
    paywall: PaywallState,
    offers: List<PlanOffer>,
    onSelect: (PlanOffer) -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Navy,
        contentColor = TextPrimary,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Upgrade your plan", style = MaterialTheme.typography.titleLarge)
            Text(paywall.reason, color = TextMuted, style = MaterialTheme.typography.bodyMedium)

            PLANS.forEach { plan ->
                val offer = offers.firstOrNull { it.tier == plan.tier }
                val isCurrent = plan.tier == currentTier
                val highlight = plan.tier == paywall.requiredTier
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface1),
                    border = BorderStroke(if (highlight) 1.5.dp else 1.dp, if (highlight) Neon else Border),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(plan.tier.label(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text(plan.tagline, color = TextMuted, style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                if (plan.tier == Tier.FREE) "Free" else offer?.let { "${it.price}/mo" } ?: "-",
                                color = Neon, fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        plan.features.forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(16.dp))
                                Text(f, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        when {
                            isCurrent -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Current plan") }
                            plan.tier.ordinal < currentTier.ordinal -> Unit
                            offer == null -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Unavailable") }
                            else -> Button(
                                onClick = { onSelect(offer) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Navy),
                            ) { Text("Choose ${plan.tier.label()}", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            TextButton(onClick = onRestore, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Restore purchases", color = Neon) }
            Text(
                "Subscriptions renew monthly through Google Play and can be cancelled anytime in the Play Store.",
                color = TextMuted, style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
