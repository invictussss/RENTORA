// ==========================================
// RENTORA - SMART VEHICLE RENTAL SYSTEM
// ==========================================

let currentUser = null;
let vehicles = [];
let selectedFilter = "All";
let selectedVehicle = null;


// ==========================================
// API HELPER
// ==========================================

async function apiRequest(url, options = {}) {
    const response = await fetch(url, {
        credentials: "same-origin",
        ...options,
        headers: {
            "Content-Type": "application/json",
            ...(options.headers || {})
        }
    });

    const data = await response.json().catch(() => ({}));

    if (!response.ok) {
        throw new Error(data.message || "Something went wrong.");
    }

    return data;
}


// ==========================================
// AUTHENTICATION
// ==========================================

async function checkAuth() {
    try {
        currentUser = await apiRequest("/api/auth/me");

        document.getElementById("guestButtons").style.display = "none";
        document.getElementById("userButtons").style.display = "flex";

        document.getElementById("navUserName").textContent =
            currentUser.name || "My Account";

        document.getElementById("profileName").textContent =
            currentUser.name || "-";

        document.getElementById("profileEmail").textContent =
            currentUser.email || "-";

        document.getElementById("profilePhone").textContent =
            currentUser.phone || "-";

        loadBookings();

    } catch (error) {
        currentUser = null;

        document.getElementById("guestButtons").style.display = "flex";
        document.getElementById("userButtons").style.display = "none";

        document.getElementById("bookingGrid").innerHTML =
            `<p class="empty-message">Please login to view your bookings.</p>`;
    }
}


// ==========================================
// LOGIN / SIGNUP MODALS
// ==========================================

function openAuthModal(type = "login") {
    document.getElementById("authModal").style.display = "flex";

    if (type === "signup") {
        showSignup();
    } else {
        showLogin();
    }
}

function closeAuthModal() {
    document.getElementById("authModal").style.display = "none";
}

function showLogin() {
    document.getElementById("loginPanel").style.display = "block";
    document.getElementById("signupPanel").style.display = "none";

    document.getElementById("loginMessage").textContent = "";
    document.getElementById("signupMessage").textContent = "";
}

function showSignup() {
    document.getElementById("loginPanel").style.display = "none";
    document.getElementById("signupPanel").style.display = "block";

    document.getElementById("loginMessage").textContent = "";
    document.getElementById("signupMessage").textContent = "";
}


// ==========================================
// USER LOGIN
// ==========================================

document.getElementById("loginForm").addEventListener("submit", async function (event) {
    event.preventDefault();

    const message = document.getElementById("loginMessage");
    const button = this.querySelector("button[type='submit']");

    const email = document.getElementById("loginEmail").value.trim();
    const password = document.getElementById("loginPassword").value;

    button.disabled = true;
    button.textContent = "Logging in...";
    message.textContent = "";

    try {
        await apiRequest("/api/auth/login", {
            method: "POST",
            body: JSON.stringify({ email, password })
        });

        this.reset();
        closeAuthModal();

        await checkAuth();

        alert("Welcome back to RENTORA!");

    } catch (error) {
        message.textContent = error.message;
    } finally {
        button.disabled = false;
        button.textContent = "Login";
    }
});


// ==========================================
// USER SIGNUP
// ==========================================

document.getElementById("signupForm").addEventListener("submit", async function (event) {
    event.preventDefault();

    const message = document.getElementById("signupMessage");
    const button = this.querySelector("button[type='submit']");

    const name = document.getElementById("signupName").value.trim();
    const email = document.getElementById("signupEmail").value.trim();
    const phone = document.getElementById("signupPhone").value.trim();
    const password = document.getElementById("signupPassword").value;
    const confirmPassword = document.getElementById("confirmPassword").value;

    if (password !== confirmPassword) {
        message.textContent = "Passwords do not match.";
        return;
    }

    if (password.length < 8) {
        message.textContent = "Password must contain at least 8 characters.";
        return;
    }

    button.disabled = true;
    button.textContent = "Creating Account...";
    message.textContent = "";

    try {
        await apiRequest("/api/auth/register", {
            method: "POST",
            body: JSON.stringify({
                name,
                email,
                phone,
                password
            })
        });

        this.reset();
        closeAuthModal();

        await checkAuth();

        alert("Account created successfully! Welcome to RENTORA.");

    } catch (error) {
        message.textContent = error.message;
    } finally {
        button.disabled = false;
        button.textContent = "Create Account";
    }
});


// ==========================================
// USER LOGOUT
// ==========================================

async function logoutUser() {
    try {
        await apiRequest("/api/auth/logout", {
            method: "POST",
            body: JSON.stringify({})
        });

        currentUser = null;

        closeProfile();
        closeAuthModal();

        document.getElementById("guestButtons").style.display = "flex";
        document.getElementById("userButtons").style.display = "none";

        document.getElementById("bookingGrid").innerHTML =
            `<p class="empty-message">Please login to view your bookings.</p>`;

        alert("You have been logged out.");

    } catch (error) {
        alert(error.message);
    }
}


// ==========================================
// MY PROFILE
// ==========================================

function openProfile() {
    if (!currentUser) {
        openAuthModal("login");
        return;
    }

    document.getElementById("profileModal").style.display = "flex";
}

function closeProfile() {
    document.getElementById("profileModal").style.display = "none";
}


// ==========================================
// LOAD VEHICLES
// ==========================================

async function loadVehicles() {
    const grid = document.getElementById("vehicleGrid");

    try {
        vehicles = await apiRequest("/api/vehicles");
        displayVehicles();

    } catch (error) {
        grid.innerHTML = `<p>Unable to load vehicles. Please refresh.</p>`;
    }
}


// ==========================================
// DISPLAY VEHICLES
// ==========================================

function displayVehicles() {
    const grid = document.getElementById("vehicleGrid");

    const filteredVehicles = selectedFilter === "All"
        ? vehicles
        : vehicles.filter(vehicle => vehicle.type === selectedFilter);

    if (!filteredVehicles.length) {
        grid.innerHTML = `<p>No vehicles found in this category.</p>`;
        return;
    }

    grid.innerHTML = filteredVehicles.map(vehicle => {
        const available = vehicle.available !== false;

        return `
            <div class="vehicle-card">

                <div class="vehicle-image">
                    <img
                        src="${escapeHTML(vehicle.image || "")}"
                        alt="${escapeHTML(vehicle.name)}"
                        onerror="this.style.display='none';"
                    >
                </div>

                <div class="vehicle-info">

                    <span class="vehicle-type">
                        ${escapeHTML(vehicle.type)}
                    </span>

                    <h3>${escapeHTML(vehicle.name)}</h3>

                    <div class="vehicle-price">
                        ₹${Number(vehicle.price).toLocaleString("en-IN")}
                        <span>/ day</span>
                    </div>

                    <p class="vehicle-availability">
                        ${available ? "Available" : "Not Available"}
                    </p>

                    <button
                        class="primary-btn"
                        onclick="bookVehicle(${Number(vehicle.id)})"
                        ${available ? "" : "disabled"}
                    >
                        ${available ? "Book Now →" : "Unavailable"}
                    </button>

                </div>

            </div>
        `;
    }).join("");
}


// ==========================================
// VEHICLE FILTERS
// ==========================================

document.querySelectorAll(".filter-btn").forEach(button => {
    button.addEventListener("click", function () {

        document.querySelectorAll(".filter-btn").forEach(btn => {
            btn.classList.remove("active");
        });

        this.classList.add("active");

        selectedFilter = this.dataset.type;

        displayVehicles();
    });
});


// ==========================================
// OPEN BOOKING FORM
// ==========================================

function bookVehicle(vehicleId) {

    if (!currentUser) {
        alert("Please login before booking a vehicle.");
        openAuthModal("login");
        return;
    }

    const vehicle = vehicles.find(v => Number(v.id) === Number(vehicleId));

    if (!vehicle || vehicle.available === false) {
        alert("This vehicle is currently unavailable.");
        return;
    }

    selectedVehicle = vehicle;

    document.getElementById("vehicleId").value = vehicle.id;
    document.getElementById("selectedVehicle").value = vehicle.name;

    document.getElementById("customerName").value = currentUser.name;
    document.getElementById("customerPhone").value = currentUser.phone;

    document.getElementById("customerName").readOnly = true;
    document.getElementById("customerPhone").readOnly = true;

    document.getElementById("rentalDays").value = 1;
    document.getElementById("bookingMessage").textContent = "";

    updateEstimatedTotal();

    document.getElementById("bookingModal").style.display = "flex";
}

function closeBookingForm() {
    document.getElementById("bookingModal").style.display = "none";
}


// ==========================================
// ESTIMATED TOTAL
// ==========================================

function updateEstimatedTotal() {
    if (!selectedVehicle) return;

    const days = Number(document.getElementById("rentalDays").value) || 1;

    const total = Number(selectedVehicle.price) * days;

    document.getElementById("estimatedTotal").textContent =
        "₹" + total.toLocaleString("en-IN");
}

document.getElementById("rentalDays").addEventListener("input", updateEstimatedTotal);


// ==========================================
// CREATE BOOKING
// ==========================================

document.getElementById("bookingForm").addEventListener("submit", async function (event) {
    event.preventDefault();

    if (!currentUser) {
        closeBookingForm();
        openAuthModal("login");
        return;
    }

    const button = this.querySelector("button[type='submit']");
    const message = document.getElementById("bookingMessage");

    const vehicleId = Number(document.getElementById("vehicleId").value);
    const days = Number(document.getElementById("rentalDays").value);

    if (!days || days < 1 || days > 30) {
        message.textContent = "Please select between 1 and 30 days.";
        return;
    }

    button.disabled = true;
    button.textContent = "Processing...";
    message.textContent = "";

    try {
        const result = await apiRequest("/api/bookings", {
            method: "POST",
            body: JSON.stringify({
                vehicleId,
                days,
                customerName: currentUser.name,
                customerPhone: currentUser.phone
            })
        });

        alert(result.message || "Booking created successfully!");

        this.reset();
        closeBookingForm();

        await loadVehicles();
        await loadBookings();

    } catch (error) {
        message.textContent = error.message;
    } finally {
        button.disabled = false;
        button.textContent = "Confirm Booking";
    }
});


// ==========================================
// LOAD MY BOOKINGS
// ==========================================

async function loadBookings() {
    const grid = document.getElementById("bookingGrid");

    if (!currentUser) {
        grid.innerHTML = `
            <p class="empty-message">
                Please login to view your bookings.
            </p>
        `;
        return;
    }

    grid.innerHTML = `<p>Loading your bookings...</p>`;

    try {
        const bookings = await apiRequest("/api/bookings");

        displayBookings(bookings);

    } catch (error) {
        grid.innerHTML = `<p>${escapeHTML(error.message)}</p>`;
    }
}


// ==========================================
// DISPLAY BOOKINGS
// ==========================================

function displayBookings(bookings) {
    const grid = document.getElementById("bookingGrid");

    if (!bookings || bookings.length === 0) {
        grid.innerHTML = `
            <div class="empty-message">
                <h3>No Bookings Yet</h3>
                <p>Explore our fleet and book your first ride!</p>
                <a href="#vehicles" class="primary-btn">Explore Vehicles</a>
            </div>
        `;
        return;
    }

    grid.innerHTML = bookings.map(booking => {

        const status = booking.status || "Active";
        const isActive = status.toLowerCase() === "active";

        return `
            <div class="booking-card">

                <div class="booking-card-header">
                    <h3>${escapeHTML(booking.vehicleName)}</h3>
                    <span class="booking-status ${status.toLowerCase()}">
                        ${escapeHTML(status)}
                    </span>
                </div>

                <div class="booking-card-details">

                    <p><strong>Booking ID:</strong> #${booking.bookingId}</p>

                    <p><strong>Customer:</strong>
                        ${escapeHTML(booking.customerName)}
                    </p>

                    <p><strong>Mobile:</strong>
                        ${escapeHTML(booking.customerPhone)}
                    </p>

                    <p><strong>Duration:</strong>
                        ${booking.days} Days
                    </p>

                    <p><strong>Booking Date:</strong>
                        ${escapeHTML(booking.bookingDate || "-")}
                    </p>

                    <p class="booking-amount">
                        <strong>Total Amount:</strong>
                        ₹${Number(booking.totalAmount).toLocaleString("en-IN")}
                    </p>

                </div>

                ${isActive ? `
                    <div class="booking-actions">

                        <button
                            class="cancel-booking-btn"
                            onclick="changeBookingStatus(${Number(booking.bookingId)}, 'cancel')"
                        >
                            Cancel Booking
                        </button>

                        <button
                            class="return-booking-btn"
                            onclick="changeBookingStatus(${Number(booking.bookingId)}, 'return')"
                        >
                            Return Vehicle
                        </button>

                    </div>
                ` : ""}

            </div>
        `;
    }).join("");
}


// ==========================================
// CANCEL / RETURN BOOKING
// ==========================================

async function changeBookingStatus(bookingId, action) {

    const actionName = action === "cancel" ? "cancel this booking" : "return this vehicle";

    if (!confirm(`Are you sure you want to ${actionName}?`)) {
        return;
    }

    try {
        const result = await apiRequest(
            `/api/bookings/${bookingId}/${action}`,
            {
                method: "POST",
                body: JSON.stringify({})
            }
        );

        alert(result.message || "Booking updated successfully.");

        await loadBookings();
        await loadVehicles();

    } catch (error) {
        alert(error.message);
    }
}


// ==========================================
// HTML ESCAPE
// ==========================================

function escapeHTML(value) {
    return String(value ?? "").replace(/[&<>"']/g, character => {
        const entities = {
            "&": "&amp;",
            "<": "&lt;",
            ">": "&gt;",
            '"': "&quot;",
            "'": "&#039;"
        };

        return entities[character];
    });
}


// ==========================================
// INITIALIZE APPLICATION
// ==========================================

document.addEventListener("DOMContentLoaded", async function () {

    await loadVehicles();
    await checkAuth();

});
