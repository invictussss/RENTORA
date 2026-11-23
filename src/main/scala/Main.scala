//> using dep org.xerial:sqlite-jdbc:3.50.3.0

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import java.sql.{Connection, DriverManager}
import java.security.{SecureRandom, MessageDigest}
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.Base64
import scala.collection.mutable

object Main {

  val dbUrl = "jdbc:sqlite:rentora.db"

  val sessions = mutable.Map[String, Int]()
  val random = new SecureRandom()

  val iterations = 210000

  def getConnection(): Connection =
    DriverManager.getConnection(dbUrl)

  // DATABASE INITIALIZATION

  def initializeDatabase(): Unit = {
    Class.forName("org.sqlite.JDBC")

    val conn = getConnection()
    val statement = conn.createStatement()

    statement.executeUpdate(
      """CREATE TABLE IF NOT EXISTS vehicles (
        id INTEGER PRIMARY KEY,
        name TEXT NOT NULL,
        type TEXT NOT NULL,
        price INTEGER NOT NULL,
        image TEXT NOT NULL,
        available INTEGER NOT NULL DEFAULT 1
      )"""
    )

    statement.executeUpdate(
      """CREATE TABLE IF NOT EXISTS users (
        user_id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        email TEXT NOT NULL UNIQUE,
        phone TEXT NOT NULL,
        password_hash TEXT NOT NULL,
        salt TEXT NOT NULL,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP
      )"""
    )

    statement.executeUpdate(
      """CREATE TABLE IF NOT EXISTS bookings (
        booking_id INTEGER PRIMARY KEY AUTOINCREMENT,
        vehicle_id INTEGER NOT NULL,
        customer_name TEXT NOT NULL,
        customer_phone TEXT NOT NULL,
        days INTEGER NOT NULL,
        total_amount INTEGER NOT NULL,
        booking_date TEXT DEFAULT CURRENT_TIMESTAMP,
        status TEXT DEFAULT 'Active',
        user_id INTEGER,
        FOREIGN KEY(vehicle_id) REFERENCES vehicles(id),
        FOREIGN KEY(user_id) REFERENCES users(user_id)
      )"""
    )

    // Migration for existing databases
    val columns = statement.executeQuery("PRAGMA table_info(bookings)")
    var hasUserId = false

    while (columns.next()) {
      if (columns.getString("name") == "user_id") {
        hasUserId = true
      }
    }

    columns.close()

    if (!hasUserId) {
      statement.executeUpdate(
        "ALTER TABLE bookings ADD COLUMN user_id INTEGER"
      )
    }

    statement.executeUpdate(
      "CREATE INDEX IF NOT EXISTS idx_bookings_user ON bookings(user_id)"
    )

    val count = statement.executeQuery("SELECT COUNT(*) FROM vehicles")
    count.next()

    if (count.getInt(1) == 0) {
      val vehicles = Seq(
        (1, "Hyundai Creta", "Car", 2500, "🚙"),
        (2, "Royal Enfield Classic 350", "Bike", 1200, "🏍️"),
        (3, "Tata Harrier", "Car", 3000, "🚘"),
        (4, "Mahindra Thar", "Car", 3500, "🚙"),
        (5, "Hero Splendor Plus", "Bike", 700, "🏍️"),
        (6, "Tata 407", "Truck", 4500, "🚚")
      )

      val insert = conn.prepareStatement(
        "INSERT INTO vehicles VALUES (?, ?, ?, ?, ?, 1)"
      )

      vehicles.foreach { v =>
        insert.setInt(1, v._1)
        insert.setString(2, v._2)
        insert.setString(3, v._3)
        insert.setInt(4, v._4)
        insert.setString(5, v._5)
        insert.executeUpdate()
      }

      insert.close()
    }

    count.close()
    statement.close()
    conn.close()

    println("RENTORA database initialized.")
  }

  // PASSWORD SECURITY

  def generateSalt(): String = {
    val bytes = new Array[Byte](16)
    random.nextBytes(bytes)
    Base64.getEncoder.encodeToString(bytes)
  }

  def hashPassword(password: String, salt: String): String = {
    val spec = new PBEKeySpec(
      password.toCharArray,
      Base64.getDecoder.decode(salt),
      iterations,
      256
    )

    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")

    val hash = factory.generateSecret(spec).getEncoded
    spec.clearPassword()

    Base64.getEncoder.encodeToString(hash)
  }

  def verifyPassword(password: String, salt: String, expected: String): Boolean = {
    val actual = hashPassword(password, salt)

    MessageDigest.isEqual(
      actual.getBytes(StandardCharsets.UTF_8),
      expected.getBytes(StandardCharsets.UTF_8)
    )
  }

  // JSON HELPERS

  def getString(body: String, key: String): Option[String] = {
    val pattern =
      ("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").r

    pattern.findFirstMatchIn(body).map(_.group(1))
  }

  def getInt(body: String, key: String): Option[Int] = {
    val pattern =
      ("\"" + key + "\"\\s*:\\s*(\\d+)").r

    pattern.findFirstMatchIn(body).map(_.group(1).toInt)
  }

  def escape(value: String): String =
    Option(value).getOrElse("")
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")

  // HTTP RESPONSE

  def send(
    exchange: HttpExchange,
    status: Int,
    response: String
  ): Unit = {

    val bytes = response.getBytes(StandardCharsets.UTF_8)

    exchange.getResponseHeaders.set(
      "Content-Type",
      "application/json; charset=UTF-8"
    )

    exchange.sendResponseHeaders(status, bytes.length)

    val output = exchange.getResponseBody
    output.write(bytes)
    output.close()
  }

  // SESSION MANAGEMENT

  def createSession(userId: Int): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)

    val token = Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(bytes)

    sessions.synchronized {
      sessions(token) = userId
    }

    token
  }

  def getSessionToken(exchange: HttpExchange): Option[String] = {
    Option(exchange.getRequestHeaders.getFirst("Cookie"))
      .flatMap { cookies =>
        cookies.split(";").map(_.trim).find(_.startsWith("rentora_session="))
      }
      .map(_.substring("rentora_session=".length))
  }

  def getLoggedInUser(exchange: HttpExchange): Option[Int] = {
    getSessionToken(exchange).flatMap { token =>
      sessions.synchronized {
        sessions.get(token)
      }
    }
  }

  def setSessionCookie(exchange: HttpExchange, token: String): Unit = {
    exchange.getResponseHeaders.set(
      "Set-Cookie",
      s"rentora_session=$token; Path=/; HttpOnly; SameSite=Lax"
    )
  }

  def clearSessionCookie(exchange: HttpExchange): Unit = {
    exchange.getResponseHeaders.set(
      "Set-Cookie",
      "rentora_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
    )
  }

  // SIGNUP

  def registerUser(exchange: HttpExchange): Unit = {

    val body = new String(
      exchange.getRequestBody.readAllBytes(),
      StandardCharsets.UTF_8
    )

    val name = getString(body, "name").map(_.trim)
    val email = getString(body, "email").map(_.trim.toLowerCase)
    val phone = getString(body, "phone").map(_.trim)
    val password = getString(body, "password")

    if (name.isEmpty || email.isEmpty || phone.isEmpty || password.isEmpty) {
      send(exchange, 400, """{"message":"All fields are required"}""")
      return
    }

    if (name.get.isEmpty ||
        !email.get.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$") ||
        !phone.get.matches("[0-9]{10}") ||
        password.get.length < 8) {

      send(exchange, 400,
        """{"message":"Enter valid details. Password must have at least 8 characters."}""")
      return
    }

    val salt = generateSalt()
    val passwordHash = hashPassword(password.get, salt)

    val conn = getConnection()

    try {
      val insert = conn.prepareStatement(
        """INSERT INTO users
          (name, email, phone, password_hash, salt)
          VALUES (?, ?, ?, ?, ?)""",
        java.sql.Statement.RETURN_GENERATED_KEYS
      )

      insert.setString(1, name.get)
      insert.setString(2, email.get)
      insert.setString(3, phone.get)
      insert.setString(4, passwordHash)
      insert.setString(5, salt)

      insert.executeUpdate()

      val keys = insert.getGeneratedKeys()
      keys.next()

      val userId = keys.getInt(1)
      val token = createSession(userId)

      setSessionCookie(exchange, token)

      send(exchange, 201,
        s"""{"message":"Account created successfully","userId":$userId,"name":"${escape(name.get)}"}""")

      keys.close()
      insert.close()

    } catch {
      case _: java.sql.SQLException =>
        send(exchange, 409,
          """{"message":"Email address is already registered"}""")
    } finally {
      conn.close()
    }
  }

  // LOGIN

  def loginUser(exchange: HttpExchange): Unit = {

    val body = new String(
      exchange.getRequestBody.readAllBytes(),
      StandardCharsets.UTF_8
    )

    val email = getString(body, "email").map(_.trim.toLowerCase)
    val password = getString(body, "password")

    if (email.isEmpty || password.isEmpty) {
      send(exchange, 400,
        """{"message":"Email and password are required"}""")
      return
    }

    val conn = getConnection()

    try {
      val query = conn.prepareStatement(
        "SELECT user_id, name, password_hash, salt FROM users WHERE email = ?"
      )

      query.setString(1, email.get)

      val rs = query.executeQuery()

      if (!rs.next()) {
        send(exchange, 401,
          """{"message":"Invalid email or password"}""")
      } else if (!verifyPassword(
        password.get,
        rs.getString("salt"),
        rs.getString("password_hash")
      )) {
        send(exchange, 401,
          """{"message":"Invalid email or password"}""")
      } else {

        val userId = rs.getInt("user_id")
        val name = rs.getString("name")
        val token = createSession(userId)

        setSessionCookie(exchange, token)

        send(exchange, 200,
          s"""{"message":"Login successful","userId":$userId,"name":"${escape(name)}"}""")
      }

      rs.close()
      query.close()

    } finally {
      conn.close()
    }
  }

  // LOGOUT

  def logoutUser(exchange: HttpExchange): Unit = {

    getSessionToken(exchange).foreach { token =>
      sessions.synchronized {
        sessions.remove(token)
      }
    }

    clearSessionCookie(exchange)

    send(exchange, 200, """{"message":"Logged out successfully"}""")
  }

  // CURRENT USER

  def getCurrentUserJson(userId: Int): String = {
    val conn = getConnection()

    val query = conn.prepareStatement(
      "SELECT user_id, name, email, phone FROM users WHERE user_id = ?"
    )

    query.setInt(1, userId)

    val rs = query.executeQuery()

    val result =
      if (rs.next()) {
        s"""{"userId":${rs.getInt("user_id")},"name":"${escape(rs.getString("name"))}","email":"${escape(rs.getString("email"))}","phone":"${escape(rs.getString("phone"))}"}"""
      } else {
        "{}"
      }

    rs.close()
    query.close()
    conn.close()

    result
  }

  // VEHICLES

  def getVehiclesJson(): String = {

    val conn = getConnection()

    val rs = conn.createStatement().executeQuery(
      "SELECT * FROM vehicles ORDER BY id"
    )

    val result = mutable.ArrayBuffer[String]()

    while (rs.next()) {
      result +=
        s"""{"id":${rs.getInt("id")},"name":"${escape(rs.getString("name"))}","type":"${escape(rs.getString("type"))}","price":${rs.getInt("price")},"image":"${escape(rs.getString("image"))}","available":${rs.getInt("available") == 1}}"""
    }

    rs.close()
    conn.close()

    result.mkString("[", ",", "]")
  }

  // CUSTOMER BOOKINGS

  def getBookingsJson(userId: Int): String = {

    val conn = getConnection()

    val query = conn.prepareStatement(
      """SELECT b.*, v.name AS vehicle_name
        FROM bookings b
        JOIN vehicles v ON b.vehicle_id = v.id
        WHERE b.user_id = ?
        ORDER BY b.booking_id DESC"""
    )

    query.setInt(1, userId)

    val rs = query.executeQuery()

    val result = mutable.ArrayBuffer[String]()

    while (rs.next()) {
      result +=
        s"""{"bookingId":${rs.getInt("booking_id")},"vehicleId":${rs.getInt("vehicle_id")},"vehicleName":"${escape(rs.getString("vehicle_name"))}","customerName":"${escape(rs.getString("customer_name"))}","customerPhone":"${escape(rs.getString("customer_phone"))}","days":${rs.getInt("days")},"totalAmount":${rs.getInt("total_amount")},"bookingDate":"${escape(rs.getString("booking_date"))}","status":"${escape(rs.getString("status"))}"}"""
    }

    rs.close()
    query.close()
    conn.close()

    result.mkString("[", ",", "]")
  }

  // CREATE BOOKING

  def createBooking(exchange: HttpExchange, userId: Int): Unit = {

    val body = new String(
      exchange.getRequestBody.readAllBytes(),
      StandardCharsets.UTF_8
    )

    val vehicleId = getInt(body, "vehicleId")
    val days = getInt(body, "days")

    if (vehicleId.isEmpty || days.isEmpty ||
        days.get < 1 || days.get > 30) {

      send(exchange, 400,
        """{"message":"Invalid booking details"}""")
      return
    }

    val conn = getConnection()
    conn.setAutoCommit(false)

    try {
      val userQuery = conn.prepareStatement(
        "SELECT name, phone FROM users WHERE user_id = ?"
      )

      userQuery.setInt(1, userId)

      val user = userQuery.executeQuery()

      if (!user.next()) {
        conn.rollback()
        send(exchange, 401, """{"message":"Please login again"}""")
        return
      }

      val customerName = user.getString("name")
      val customerPhone = user.getString("phone")

      val check = conn.prepareStatement(
        "SELECT price, available FROM vehicles WHERE id = ?"
      )

      check.setInt(1, vehicleId.get)

      val rs = check.executeQuery()

      if (!rs.next()) {
        conn.rollback()
        send(exchange, 404, """{"message":"Vehicle not found"}""")
      } else if (rs.getInt("available") != 1) {
        conn.rollback()
        send(exchange, 409, """{"message":"Vehicle is already booked"}""")
      } else {

        val total = rs.getInt("price") * days.get

        val update = conn.prepareStatement(
          "UPDATE vehicles SET available = 0 WHERE id = ? AND available = 1"
        )

        update.setInt(1, vehicleId.get)

        if (update.executeUpdate() == 0) {
          conn.rollback()
          send(exchange, 409, """{"message":"Vehicle is already booked"}""")
        } else {

          val insert = conn.prepareStatement(
            """INSERT INTO bookings
              (vehicle_id, customer_name, customer_phone, days, total_amount, user_id)
              VALUES (?, ?, ?, ?, ?, ?)""",
            java.sql.Statement.RETURN_GENERATED_KEYS
          )

          insert.setInt(1, vehicleId.get)
          insert.setString(2, customerName)
          insert.setString(3, customerPhone)
          insert.setInt(4, days.get)
          insert.setInt(5, total)
          insert.setInt(6, userId)

          insert.executeUpdate()

          val keys = insert.getGeneratedKeys()
          keys.next()

          val bookingId = keys.getInt(1)

          conn.commit()

          send(exchange, 201,
            s"""{"message":"Booking confirmed","bookingId":$bookingId,"totalAmount":$total}""")

          keys.close()
          insert.close()
          update.close()
        }
      }

      rs.close()
      check.close()
      user.close()
      userQuery.close()

    } catch {
      case e: Exception =>
        conn.rollback()
        e.printStackTrace()
        send(exchange, 500,
          """{"message":"Booking could not be saved"}""")
    } finally {
      conn.close()
    }
  }

  // CANCEL / RETURN BOOKING

  def updateBookingStatus(
    exchange: HttpExchange,
    bookingId: Int,
    newStatus: String,
    userId: Int
  ): Unit = {

    val conn = getConnection()
    conn.setAutoCommit(false)

    try {
      val check = conn.prepareStatement(
        "SELECT vehicle_id, status FROM bookings WHERE booking_id = ? AND user_id = ?"
      )

      check.setInt(1, bookingId)
      check.setInt(2, userId)

      val rs = check.executeQuery()

      if (!rs.next()) {
        conn.rollback()
        send(exchange, 404, """{"message":"Booking not found"}""")
      } else if (rs.getString("status") != "Active") {
        conn.rollback()
        send(exchange, 409,
          """{"message":"Only active bookings can be updated"}""")
      } else {

        val vehicleId = rs.getInt("vehicle_id")

        val updateBooking = conn.prepareStatement(
          "UPDATE bookings SET status = ? WHERE booking_id = ? AND user_id = ?"
        )

        updateBooking.setString(1, newStatus)
        updateBooking.setInt(2, bookingId)
        updateBooking.setInt(3, userId)
        updateBooking.executeUpdate()

        val updateVehicle = conn.prepareStatement(
          "UPDATE vehicles SET available = 1 WHERE id = ?"
        )

        updateVehicle.setInt(1, vehicleId)
        updateVehicle.executeUpdate()

        conn.commit()

        send(exchange, 200,
          s"""{"message":"Booking $newStatus successfully","bookingId":$bookingId,"status":"$newStatus"}""")

        updateBooking.close()
        updateVehicle.close()
      }

      rs.close()
      check.close()

    } catch {
      case e: Exception =>
        conn.rollback()
        e.printStackTrace()
        send(exchange, 500,
          """{"message":"Could not update booking"}""")
    } finally {
      conn.close()
    }
  }

  // STATIC FILES

  def serveStatic(exchange: HttpExchange, path: String): Unit = {

    val base = Paths.get("src/main/resources/static")
      .toAbsolutePath.normalize()

    val requestedPath =
      if (path == "/") "index.html"
      else path.stripPrefix("/")

    val file = base.resolve(requestedPath).normalize()

    if (file.startsWith(base) &&
        Files.exists(file) && !Files.isDirectory(file)) {

      val content = Files.readAllBytes(file)

      val contentType =
        if (file.toString.endsWith(".html")) "text/html"
        else if (file.toString.endsWith(".css")) "text/css"
        else if (file.toString.endsWith(".js")) "application/javascript"
        else "text/plain"

      exchange.getResponseHeaders.set(
        "Content-Type",
        contentType + "; charset=UTF-8"
      )

      exchange.sendResponseHeaders(200, content.length)

      val output = exchange.getResponseBody
      output.write(content)
      output.close()

    } else {
      send(exchange, 404, """{"message":"Page not found"}""")
    }
  }

  // SERVER

  def main(args: Array[String]): Unit = {

    initializeDatabase()

    val server = HttpServer.create(
      new InetSocketAddress(8080), 0
    )

    server.createContext("/", exchange => {

      try {
        val path = exchange.getRequestURI.getPath
        val method = exchange.getRequestMethod

        (method, path) match {

          case ("POST", "/api/auth/register") =>
            registerUser(exchange)

          case ("POST", "/api/auth/login") =>
            loginUser(exchange)

          case ("POST", "/api/auth/logout") =>
            logoutUser(exchange)

          case ("GET", "/api/auth/me") =>
            getLoggedInUser(exchange) match {
              case Some(userId) =>
                send(exchange, 200, getCurrentUserJson(userId))
              case None =>
                send(exchange, 401, """{"message":"Not logged in"}""")
            }

          case ("GET", "/api/vehicles") =>
            send(exchange, 200, getVehiclesJson())

          case ("GET", "/api/bookings") =>
            getLoggedInUser(exchange) match {
              case Some(userId) =>
                send(exchange, 200, getBookingsJson(userId))
              case None =>
                send(exchange, 401, """{"message":"Please login to view bookings"}""")
            }

          case ("POST", "/api/bookings") =>
            getLoggedInUser(exchange) match {
              case Some(userId) =>
                createBooking(exchange, userId)
              case None =>
                send(exchange, 401, """{"message":"Please login before booking"}""")
            }

          case ("POST", p) if p.matches("/api/bookings/[0-9]+/cancel") =>
            getLoggedInUser(exchange) match {
              case Some(userId) =>
                updateBookingStatus(exchange, p.split("/")(3).toInt, "Cancelled", userId)
              case None =>
                send(exchange, 401, """{"message":"Please login first"}""")
            }

          case ("POST", p) if p.matches("/api/bookings/[0-9]+/return") =>
            getLoggedInUser(exchange) match {
              case Some(userId) =>
                updateBookingStatus(exchange, p.split("/")(3).toInt, "Returned", userId)
              case None =>
                send(exchange, 401, """{"message":"Please login first"}""")
            }

          case _ =>
            serveStatic(exchange, path)
        }

      } catch {
        case e: Exception =>
          e.printStackTrace()
          send(exchange, 500,
            """{"message":"Internal server error"}""")
      }
    })

    server.start()

    println("====================================")
    println("       RENTORA SERVER STARTED")
    println("       http://localhost:8080")
    println("       Authentication Enabled")
    println("====================================")
  }
}
