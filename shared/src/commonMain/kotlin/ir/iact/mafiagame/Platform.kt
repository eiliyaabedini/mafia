package ir.iact.mafiagame

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform