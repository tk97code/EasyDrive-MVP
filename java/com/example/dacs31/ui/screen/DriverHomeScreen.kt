package com.example.dacs31.ui.screen

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.dacs31.R
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck

@Composable
fun DriverHomeScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var pointAnnotationManager by remember { mutableStateOf<PointAnnotationManager?>(null) }
    var userLocation by remember { mutableStateOf<Point?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }

    // Firebase Realtime Database
    val database = Firebase.database
    val connectedRef = database.getReference("driver_status/connected")

    // Lắng nghe thay đổi từ Firebase để cập nhật trạng thái isConnected
    LaunchedEffect(Unit) {
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val value = snapshot.getValue(Boolean::class.java)
                if (value != null) {
                    isConnected = value
                    Log.d("Firebase", "Trạng thái isConnected cập nhật từ Firebase: $value")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "Lỗi khi đọc dữ liệu: ${error.message}")
            }
        })

        // Kiểm tra kết nối Firebase bằng cách ghi dữ liệu thử nghiệm
        val testRef = database.getReference("test_message")
        testRef.setValue("Hello, Firebase!")
            .addOnSuccessListener {
                Log.d("FirebaseTest", "Ghi dữ liệu thành công!")
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseTest", "Ghi dữ liệu thất bại: ${e.message}")
            }
    }

    // Xin quyền vị trí
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showPermissionDeniedDialog = true
        }
    }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Hiển thị dialog nếu quyền vị trí bị từ chối
    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = { Text("Quyền vị trí bị từ chối") },
            text = { Text("Ứng dụng cần quyền vị trí để hiển thị bản đồ và vị trí của bạn. Vui lòng cấp quyền trong cài đặt.") },
            confirmButton = {
                TextButton(onClick = { showPermissionDeniedDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Quản lý lifecycle cho mapView
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView?.onStart()
                Lifecycle.Event.ON_STOP -> mapView?.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Sử dụng Box để xếp chồng các thành phần lên trên bản đồ
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Bản đồ làm nền, chiếm toàn màn hình
        AndroidView(
            factory = { context ->
                MapView(context).apply {
                    // Khởi tạo MapboxMap từ MapView
                    val mapboxMap = getMapboxMap()

                    mapboxMap.loadStyleUri(Style.MAPBOX_STREETS) { style ->
                        // Đăng ký Bitmap vào Style
                        val bitmap = context.getBitmapFromVectorDrawable(R.drawable.baseline_location_on_24)
                        val imageId = "user-location-marker"
                        style.addImage(imageId, bitmap)

                        location.updateSettings {
                            enabled = true
                            locationPuck = createDefault2DPuck()
                            pulsingEnabled = true
                        }

                        // Lắng nghe vị trí người dùng
                        location.addOnIndicatorPositionChangedListener { point ->
                            userLocation = point

                            // Di chuyển camera về vị trí người dùng
                            mapboxMap.setCamera(
                                CameraOptions.Builder()
                                    .center(point)
                                    .zoom(15.0)
                                    .build()
                            )

                            // Thêm marker
                            if (pointAnnotationManager == null) {
                                val annotationApi = annotations
                                pointAnnotationManager = annotationApi.createPointAnnotationManager()
                            }

                            pointAnnotationManager?.deleteAll()

                            // Sử dụng ID của hình ảnh đã đăng ký
                            val pointAnnotationOptions = PointAnnotationOptions()
                                .withPoint(point)
                                .withIconImage(imageId)
                            pointAnnotationManager?.create(pointAnnotationOptions)
                        }
                    }
                    mapView = this // Gán giá trị cho mapView
                }
            },
            modifier = Modifier.fillMaxSize() // Bản đồ chiếm toàn màn hình
        )

        // Thanh điều khiển phía trên (Menu, Search, Notification)
        TopControlBar(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.TopCenter)
        )

        // Column để chứa nút Connect/Disconnect và thanh điều hướng
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Nút Connect/Disconnect và thanh điều hướng
            BottomControlBar(
                navController = navController,
                isConnected = isConnected,
                onConnectClick = {
                    isConnected = !isConnected
                    // Cập nhật trạng thái lên Firebase
                    connectedRef.setValue(isConnected)
                        .addOnSuccessListener {
                            Log.d("Firebase", "Cập nhật trạng thái isConnected thành công: $isConnected")
                        }
                        .addOnFailureListener { e ->
                            Log.e("Firebase", "Cập nhật trạng thái thất bại: ${e.message}")
                        }
                }
            )
        }
    }
}

// Thanh điều khiển phía trên (Menu, Search, Notification)
@Composable
fun TopControlBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Nút Menu (trái)
        IconButton(
            onClick = { /* Xử lý mở menu */ },
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                )
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Menu",
                tint = Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }

        // Spacer chiếm phần giữa để nút bên trái và phải sát mép
        Spacer(modifier = Modifier.weight(1f))

        // Cụm nút Search + Notification (phải)
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { /* Xử lý tìm kiếm */ },
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = { /* Xử lý thông báo */ },
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = "Notifications",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// Thanh điều khiển phía dưới (nút Connect/Disconnect và Bottom Navigation)
@Composable
fun BottomControlBar(
    navController: NavController,
    isConnected: Boolean,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Nút Connect/Disconnect
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = onConnectClick,
                modifier = Modifier
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color.Red else Color.Black
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Power,
                        contentDescription = "Connect",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isConnected) "Disconnect" else "Connect",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        // Thanh điều hướng dưới cùng
        BottomNavigationBar(
            navController = navController,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
        )
    }
}

// Chuyển drawable thành Bitmap (để dùng làm marker icon)
fun Context.getBitmapFromVectorDrawable(drawableId: Int): Bitmap {
    val drawable = ContextCompat.getDrawable(this, drawableId)!!
    val bitmap = Bitmap.createBitmap(
        drawable.intrinsicWidth,
        drawable.intrinsicHeight,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

@Composable
fun BottomNavigationBar(navController: NavController, modifier: Modifier = Modifier) {
    val items = listOf(
        "Home" to Icons.Default.Home,
        "Favourite" to Icons.Default.FavoriteBorder,
        "Wallet" to null, // Drawable riêng
        "Offer" to Icons.Default.LocalOffer,
        "Profile" to Icons.Default.Person
    )

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp)
            .background(Color.Transparent)
            .shadow(8.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.Bottom
        ) {
            items.forEach { (title, icon) ->
                val route = title.lowercase()
                val isSelected = currentRoute == route

                if (title == "Wallet") {
                    // Wallet: to hơn, sát dưới
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .offset(y = 0.dp) // Sát đáy luôn
                            .clickable {
                                if (currentRoute != route) {
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_wallet_hexagon),
                            contentDescription = title,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(110.dp) // To hơn
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            if (currentRoute != route) {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = icon!!,
                            contentDescription = title,
                            tint = if (isSelected) Color(0xFFFFB800) else Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = title,
                            color = if (isSelected) Color(0xFFFFB800) else Color.Gray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}