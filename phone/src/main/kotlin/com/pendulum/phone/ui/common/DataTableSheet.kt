package com.pendulum.phone.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * The same content as a chart, in the shape of a table.
 *
 * Two uses that turn out to be one:
 *
 * - **the accessible alternative.** A `contentDescription` on a canvas summarises; it does not
 *   render data. A screen reader needs a table, and it is the only path that is really usable
 *   without sight.
 * - **the "I want the exact figure" mode.** The intended user is a technician; refusing them the
 *   precise value on the grounds that it is uncertain would be both condescending and
 *   counter-productive — they need it to check that the measurement worked.
 *
 * Both needs have exactly the same answer, which is the sign that it is the right one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTableSheet(
    columns: List<String>,
    rows: List<List<String>>,
    onClose: () -> Unit,
) {
    val c = LocalPendulumColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = PendulumShapes.sheet,
        containerColor = c.surfaceElevated,
    ) {
        Column(Modifier.padding(horizontal = Spacing.m.dp).padding(bottom = Spacing.l.dp)) {
            Text(stringResource(R.string.chart_values), style = PendulumType.titleM, color = c.textPrimary)
            Text(stringResource(R.string.chart_values_note), style = PendulumType.caption, color = c.textTertiary)
            Spacer(Modifier.height(Spacing.sm.dp))

            val scroll = rememberScrollState()
            Column(Modifier.horizontalScroll(scroll)) {
                Row(Modifier.fillMaxWidth()) {
                    columns.forEach {
                        Text(it, style = PendulumType.label, color = c.textTertiary, modifier = Modifier.width(96.dp))
                    }
                }
                Spacer(Modifier.height(Spacing.xs.dp))
                LazyColumn {
                    items(rows) { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            row.forEach {
                                // Tabular figures: without them the columns do not line up.
                                Text(it, style = PendulumType.bodyNum, color = c.textPrimary, modifier = Modifier.width(96.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
