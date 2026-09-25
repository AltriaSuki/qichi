package app.qichi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.component.decor.Illustration
import app.qichi.core.designsystem.component.decor.Scene
import app.qichi.core.network.FileUrls
import app.qichi.shared.api.Plan
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.util.UUID

/** 计划没有封面照片时用的插画：按 id 固定选一幅，同一个计划每次都一样。 */
fun planScene(id: UUID): Scene = Scene.entries[Math.floorMod(id.hashCode(), Scene.entries.size)]

/**
 * 计划封面（P10-06）：选了照片就显示照片（先铺插画，照片出来盖上去；离线读不到时仍是插画），没选就是插画。
 */
@Composable
fun PlanCover(plan: Plan, urls: FileUrls, modifier: Modifier = Modifier, shape: Shape, thumbWidth: Int = 400) {
    Box(modifier.clip(shape).background(QichiTheme.colors.surface)) {
        Illustration(planScene(plan.id), Modifier.fillMaxSize())
        plan.coverFileId?.let { file ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(urls.thumbnail(file, thumbWidth)).crossfade(!QichiTheme.reduceMotion).build(),
                contentDescription = "计划封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
