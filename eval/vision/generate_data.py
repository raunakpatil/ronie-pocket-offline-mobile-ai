"""Generates the 80 test images for the vision eval suite:
- 20 synthetic receipts (thermal paper style, items, totals, GST)
- 15 synthetic documents (tickets, bills, letters, notices)
- 15 synthetic screenshots (mobile/desktop dialogs, widgets, errors)
- 20 photos (downloaded CC0 / public domain from Wikimedia Commons)
- 6 image + tool scenarios (posters, bills, flyers)
- 4 honesty cases (blurred text, cut-off receipt, abstract pattern, medicine label)
"""
import os
import random
import subprocess
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

DATA_DIR = Path(__file__).parent / "data"
DATA_DIR.mkdir(parents=True, exist_ok=True)

# Try standard fonts
def get_font(size=20, bold=False):
    win_fonts = ["C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
                 "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
                 "C:/Windows/Fonts/consola.ttf"]
    for f in win_fonts:
        if os.path.exists(f):
            try:
                return ImageFont.truetype(f, size)
            except Exception:
                pass
    return ImageFont.load_default()

def get_indic_font(size=20):
    for f in ["C:/Windows/Fonts/Nirmala.ttc", "C:/Windows/Fonts/mangal.ttf"]:
        if os.path.exists(f):
            try:
                return ImageFont.truetype(f, size)
            except Exception:
                pass
    return get_font(size)

def add_noise_and_folds(img, rotation=0, noise=True):
    if rotation:
        img = img.rotate(rotation, expand=True, fillcolor=(245, 245, 242))
    return img

RECEIPT_DATA = [
    ("D-Mart Supermarket", "2026-11-04", "INR", [("Basmati Rice 5kg", 1, 450.0), ("Toor Dal 1kg", 2, 160.0), ("Sunflower Oil 1L", 1, 145.0)], 0.05),
    ("Starbucks Coffee", "2026-11-05", "INR", [("Caffe Latte Grande", 1, 345.0), ("Butter Croissant", 1, 225.0)], 0.05),
    ("Apollo Pharmacy", "2026-11-03", "INR", [("Paracetamol 650", 2, 35.0), ("Vitamin C 500mg", 1, 85.0), ("Bandage Roll", 1, 45.0)], 0.12),
    ("Blue Tokai Coffee", "2026-11-06", "INR", [("Cold Brew Bottle", 2, 210.0), ("Almond Biscotti", 1, 120.0)], 0.05),
    ("Reliance Fresh", "2026-11-02", "INR", [("Fresh Milk 1L", 2, 66.0), ("Whole Wheat Bread", 1, 45.0), ("Eggs 6pk", 1, 55.0)], 0.05),
    ("Haldiram's Sweets", "2026-11-01", "INR", [("Kaju Katli 250g", 1, 280.0), ("Raj Kachori", 2, 140.0), ("Gulab Jamun 2pc", 1, 90.0)], 0.05),
    ("Nature's Basket", "2026-11-07", "INR", [("Avocado Hass 2pc", 1, 299.0), ("Greek Yogurt 400g", 1, 185.0), ("Sourdough Loaf", 1, 160.0)], 0.05),
    ("Costa Coffee", "2026-11-08", "INR", [("Flat White", 1, 290.0), ("Blueberry Muffin", 1, 210.0)], 0.05),
    ("McDonald's", "2026-11-04", "INR", [("McVeggie Burger", 2, 135.0), ("Fries Medium", 1, 115.0), ("Coke 500ml", 1, 85.0)], 0.05),
    ("KFC Restaurant", "2026-11-05", "INR", [("Zinger Burger", 1, 199.0), ("Popcorn Chicken Med", 1, 189.0), ("Pepsi 400ml", 1, 65.0)], 0.05),
    ("Fabindia Crafts", "2026-11-02", "INR", [("Cotton Kurta Blue", 1, 1490.0), ("Incense Sticks Pack", 2, 150.0)], 0.12),
    ("Subway Sandwiches", "2026-11-06", "INR", [("Paneer Tikka Sub 6in", 1, 235.0), ("Cookie Chocochip", 2, 50.0), ("Iced Tea", 1, 75.0)], 0.05),
    ("Decathlon Sports", "2026-11-03", "INR", [("Running Socks 3pk", 1, 299.0), ("Water Bottle 750ml", 1, 199.0), ("Resistance Band", 1, 399.0)], 0.18),
    ("Chai Point", "2026-11-07", "INR", [("Ginger Chai Flask 500ml", 1, 180.0), ("Banana Cake Slice", 2, 70.0), ("Samosa 2pc", 1, 60.0)], 0.05),
    ("Blinkit Quick Mart", "2026-11-08", "INR", [("Lays Classic 50g", 2, 20.0), ("Amul Butter 100g", 1, 56.0), ("Coke Zero Can", 2, 40.0)], 0.05),
    ("Zudio Retail", "2026-11-01", "INR", [("Basic Tee Black L", 1, 299.0), ("Canvas Slip-on Shoes", 1, 499.0)], 0.05),
    ("Burger King", "2026-11-05", "INR", [("Whopper Meal", 1, 319.0), ("Crispy Veg Burger", 1, 89.0), ("Sundae Choco", 1, 69.0)], 0.05),
    ("Crossword Books", "2026-11-04", "INR", [("Atomic Habits Paperback", 1, 550.0), ("Gel Pens 3pk", 1, 150.0), ("Bookmark Metal", 1, 99.0)], 0.12),
    ("Pizza Hut", "2026-11-06", "INR", [("Margherita Medium", 1, 399.0), ("Garlic Breadstick", 1, 149.0), ("Mirinda 500ml", 1, 70.0)], 0.05),
    ("Croma Electronics", "2026-11-03", "INR", [("USB-C Fast Cable 1m", 1, 399.0), ("Wireless Mouse", 1, 599.0)], 0.18),
]

def render_receipt(idx, merchant, date, curr, items, tax_rate):
    w, h = 480, 680
    img = Image.new("RGB", (w, h), (248, 247, 242))
    draw = ImageDraw.Draw(img)

    f_title = get_font(24, bold=True)
    f_body = get_font(18, bold=False)
    f_bold = get_font(18, bold=True)
    f_small = get_font(15, bold=False)

    y = 30
    draw.text((w // 2, y), merchant, fill=(20, 20, 20), font=f_title, anchor="mt")
    y += 40
    draw.text((w // 2, y), f"Date: {date}   Receipt #{10400 + idx}", fill=(80, 80, 80), font=f_small, anchor="mt")
    y += 35
    draw.line([(30, y), (w - 30, y)], fill=(180, 180, 180), width=2)
    y += 20

    subtotal = 0.0
    for name, qty, price in items:
        line_total = qty * price
        subtotal += line_total
        draw.text((35, y), f"{qty}x  {name}", fill=(30, 30, 30), font=f_body)
        draw.text((w - 35, y), f"{line_total:.2f}", fill=(30, 30, 30), font=f_body, anchor="ra")
        y += 32

    tax_amount = round(subtotal * tax_rate, 2)
    total = round(subtotal + tax_amount, 2)

    y += 15
    draw.line([(30, y), (w - 30, y)], fill=(200, 200, 200), width=1)
    y += 20
    draw.text((35, y), "Subtotal", fill=(70, 70, 70), font=f_body)
    draw.text((w - 35, y), f"{subtotal:.2f}", fill=(70, 70, 70), font=f_body, anchor="ra")
    y += 28
    draw.text((35, y), f"Tax / GST ({int(tax_rate * 100)}%)", fill=(70, 70, 70), font=f_body)
    draw.text((w - 35, y), f"{tax_amount:.2f}", fill=(70, 70, 70), font=f_body, anchor="ra")
    y += 35
    draw.line([(30, y), (w - 30, y)], fill=(40, 40, 40), width=2)
    y += 20
    draw.text((35, y), "TOTAL", fill=(10, 10, 10), font=f_bold)
    draw.text((w - 35, y), f"{curr} {total:.2f}", fill=(10, 10, 10), font=f_bold, anchor="ra")
    y += 50
    draw.text((w // 2, y), "*** THANK YOU FOR YOUR VISIT ***", fill=(100, 100, 100), font=f_small, anchor="mt")

    # Slight rotation / thermal look
    rot = (idx % 5 - 2) * 0.7
    img = add_noise_and_folds(img, rotation=rot)
    filename = f"rec_{idx+1:03d}.jpg"
    img.save(DATA_DIR / filename, "JPEG", quality=90)
    return {
        "filename": filename,
        "merchant": merchant,
        "date": date,
        "currency": curr,
        "subtotal": subtotal,
        "tax": tax_amount,
        "total": total,
        "items": [{"name": n, "qty": q, "price": p} for n, q, p in items]
    }

def generate_receipts():
    meta = []
    for i, item in enumerate(RECEIPT_DATA):
        m = render_receipt(i, item[0], item[1], item[2], item[3], item[4])
        meta.append(m)
    return meta

def render_text_doc(filename, title, lines, header="DOCUMENT"):
    w, h = 640, 720
    img = Image.new("RGB", (w, h), (255, 255, 255))
    draw = ImageDraw.Draw(img)
    f_hdr = get_font(16, bold=True)
    f_ttl = get_font(26, bold=True)
    f_body = get_font(19, bold=False)
    f_bold = get_font(19, bold=True)

    draw.rectangle([(20, 20), (w - 20, h - 20)], outline=(220, 220, 225), width=2)
    draw.rectangle([(20, 20), (w - 20, 65)], fill=(240, 242, 248))
    draw.text((35, 32), header, fill=(70, 80, 100), font=f_hdr)

    draw.text((35, 90), title, fill=(20, 30, 50), font=f_ttl)
    draw.line([(35, 130), (w - 35, 130)], fill=(200, 205, 215), width=2)

    y = 155
    for label, val in lines:
        if label.startswith("---"):
            draw.line([(35, y + 10), (w - 35, y + 10)], fill=(230, 230, 235), width=1)
            y += 25
            continue
        draw.text((40, y), label, fill=(100, 105, 120), font=f_body)
        draw.text((260, y), val, fill=(20, 25, 35), font=f_bold)
        y += 36

    img.save(DATA_DIR / filename, "JPEG", quality=92)

def generate_documents():
    docs = [
        ("doc_001.jpg", "Flight Booking Confirmation", [
            ("Passenger Name:", "Vikram Malhotra"),
            ("Airline / Flight:", "Air India AI-804"),
            ("From / To:", "Delhi (DEL) -> Mumbai (BOM)"),
            ("Departure Time:", "14:30 IST"),
            ("Departure Gate:", "Gate B4"),
            ("Seat:", "12F (Window)"),
            ("Booking Ref (PNR):", "AI99XQ2"),
        ], "AIRLINE TICKET CONFIRMATION"),

        ("doc_002.jpg", "Electricity Utility Bill", [
            ("Consumer No:", "EB-904218"),
            ("Billing Cycle:", "October 2026"),
            ("Units Consumed:", "342 kWh"),
            ("Bill Amount Due:", "Rs 2,450.00"),
            ("Due Date:", "25 November 2026"),
            ("Tariff Category:", "Domestic LT-1"),
        ], "CITY POWER & ELECTRICITY"),

        ("doc_003.jpg", "Medical Prescription Slip", [
            ("Doctor:", "Dr. A. Sharma, MD"),
            ("Patient:", "Rahul Verma (Age 32)"),
            ("Diagnosis:", "Acute Bronchitis"),
            ("Medication:", "Amoxicillin 500mg"),
            ("Dosage / Timing:", "500mg twice daily after meals"),
            ("Duration:", "5 Days"),
            ("Follow-up Visit:", "Monday 10:00 AM"),
        ], "APOLLO CLINIC OUTPATIENT"),

        ("doc_004.jpg", "Tech Summit Timetable", [
            ("Conference:", "AI Systems World 2026"),
            ("Date:", "18 November 2026"),
            ("09:30 - 10:30:", "Keynote by Dr. Lisa Chen"),
            ("11:00 - 12:30:", "Session: On-Device Neural Agents"),
            ("12:30 - 14:00:", "Lunch & Networking"),
            ("14:00 - 16:00:", "Workshop: Quantized Transformers"),
        ], "SUMMIT PROGRAM SCHEDULE"),

        ("doc_005.jpg", "Library Overdue Notice", [
            ("Member Name:", "Siddharth Jain"),
            ("Membership No:", "LIB-4412"),
            ("Book Title:", "Structure and Interpretation of Computer Programs"),
            ("Due Date Was:", "28 October 2026"),
            ("Days Overdue:", "12 Days"),
            ("Outstanding Fine:", "Rs 120.00"),
        ], "CENTRAL PUBLIC LIBRARY"),

        ("doc_006.jpg", "Residential Lease Agreement", [
            ("Tenant:", "John Doe"),
            ("Landlord:", "Jane Smith"),
            ("Property Address:", "Flat 402, Green Acres, Pune"),
            ("Monthly Rent:", "$1,800.00"),
            ("Security Deposit:", "$3,600.00"),
            ("Lease Term:", "12 Months"),
        ], "STANDARD LEASE CONTRACT"),

        ("doc_007.jpg", "Gym Membership Certificate", [
            ("Member Name:", "Pooja Hegde"),
            ("Member ID:", "GYM-9921"),
            ("Plan Type:", "Annual Gold Fitness"),
            ("Start Date:", "01 January 2026"),
            ("Expiry Date:", "31 December 2027"),
            ("Personal Trainer:", "Marcus Vance"),
        ], "POWERHOUSE FITNESS CLUB"),

        ("doc_008.jpg", "Hotel Reservation Voucher", [
            ("Guest Name:", "Arjun Kapoor"),
            ("Hotel:", "Grand Palace Luxury Suites"),
            ("Check-in Date:", "14 December 2026"),
            ("Check-out Date:", "18 December 2026"),
            ("Room Type:", "Deluxe King Bay View"),
            ("Confirmation Code:", "HGP-774"),
        ], "BOOKING CONFIRMATION"),

        ("doc_009.jpg", "Academic Grade Report", [
            ("Student:", "Neha Gupta"),
            ("Roll Number:", "2023-CS-048"),
            ("Course Code:", "CS101 Intro to Algorithms"),
            ("Grade Awarded:", "A+"),
            ("Credits Earned:", "4 Credits"),
            ("Semester:", "Autumn 2026"),
        ], "INDIAN INSTITUTE OF TECHNOLOGY"),

        ("doc_010.jpg", "Express Delivery Consignment", [
            ("Recipient:", "Kiran Patel"),
            ("Carrier:", "BlueDart Express"),
            ("Tracking Number:", "TRK-8839201"),
            ("Destination:", "Ahmedabad, Gujarat"),
            ("Package Weight:", "1.4 kg"),
            ("Estimated Delivery:", "Tomorrow by 8 PM"),
        ], "COURIER TRACKING SLIP"),

        ("doc_011.jpg", "Auto Service Invoice", [
            ("Vehicle:", "Honda Civic 2022"),
            ("License Plate:", "MH-12-AB-4021"),
            ("Odometer Reading:", "45,210 miles"),
            ("Services Performed:", "Synthetic Oil Change & Brake Inspection"),
            ("Parts & Labor Total:", "$340.50"),
            ("Technician:", "Dave K."),
        ], "PRECISION AUTO CARE"),

        ("doc_012.jpg", "Broadband Internet Statement", [
            ("Account Name:", "Rohan Mehta"),
            ("Account ID:", "ACT-90812"),
            ("Broadband Plan:", "Fiber Ultra 300 Mbps"),
            ("Speed Rating:", "300 Mbps symmetric"),
            ("Billing Period:", "01 Nov - 30 Nov 2026"),
            ("Monthly Charge:", "$65.00"),
        ], "HYPERNET BROADBAND SERVICES"),

        ("doc_013.jpg", "Concert Admission Ticket", [
            ("Event:", "Rock Fest 2026 Live"),
            ("Venue:", "Metropolis Arena"),
            ("Date & Time:", "22 November 2026 at 7:30 PM"),
            ("Seat Details:", "Row C Seat 14"),
            ("Doors Open:", "6:00 PM"),
            ("Ticket Price:", "$85.00"),
        ], "OFFICIAL EVENT ADMISSION"),

        ("doc_014.jpg", "Employee Identification Badge", [
            ("Full Name:", "Alice Vance"),
            ("Badge ID:", "EMP-502"),
            ("Department:", "Research & Machine Intelligence"),
            ("Role Title:", "Principal Scientist"),
            ("Office Location:", "Building 4, Bangalore"),
            ("Access Level:", "Level 4 (Secure Lab)"),
        ], "CORPORATE ACCESS PASS"),

        ("doc_015.jpg", "Railway Express Schedule", [
            ("Train Number:", "12951"),
            ("Train Name:", "Mumbai Rajdhani Express"),
            ("From Station:", "New Delhi (NDLS)"),
            ("To Station:", "Mumbai Central (MMCT)"),
            ("Scheduled Departure:", "17:00 IST"),
            ("Departure Platform:", "Platform 3"),
        ], "INDIAN RAILWAYS PASSENGER TICKET"),
    ]

    for f, t, lines, hdr in docs:
        render_text_doc(f, t, lines, hdr)

def render_ui_screenshot(filename, title, widgets):
    w, h = 480, 640
    img = Image.new("RGB", (w, h), (245, 246, 250))
    draw = ImageDraw.Draw(img)

    f_top = get_font(15, bold=True)
    f_title = get_font(22, bold=True)
    f_body = get_font(17, bold=False)
    f_btn = get_font(17, bold=True)

    # Status bar
    draw.rectangle([(0, 0), (w, 36)], fill=(230, 232, 240))
    draw.text((20, 10), "9:41 AM", fill=(60, 60, 60), font=f_top)
    draw.text((w - 20, 10), "LTE   100%", fill=(60, 60, 60), font=f_top, anchor="ra")

    # App header
    draw.rectangle([(0, 36), (w, 90)], fill=(255, 255, 255))
    draw.text((20, 52), title, fill=(20, 20, 30), font=f_title)
    draw.line([(0, 90), (w, 90)], fill=(225, 227, 235), width=1)

    y = 115
    for kind, val1, val2 in widgets:
        if kind == "dialog":
            draw.rectangle([(25, y), (w - 25, y + 210)], fill=(255, 255, 255), outline=(210, 215, 225), width=2)
            draw.text((45, y + 25), val1, fill=(20, 20, 25), font=get_font(21, bold=True))
            draw.text((45, y + 70), val2, fill=(90, 95, 105), font=f_body)
            # Buttons
            draw.rectangle([(w - 145, y + 145), (w - 45, y + 190)], fill=(40, 100, 235))
            draw.text((w - 95, y + 158), "OK", fill=(255, 255, 255), font=f_btn, anchor="mt")
            draw.text((w - 180, y + 158), "Cancel", fill=(80, 85, 95), font=f_btn, anchor="mt")
            y += 230
        elif kind == "error_box":
            draw.rectangle([(25, y), (w - 25, y + 180)], fill=(254, 242, 242), outline=(248, 113, 113), width=2)
            draw.text((45, y + 25), val1, fill=(153, 27, 27), font=get_font(20, bold=True))
            draw.text((45, y + 65), val2, fill=(185, 28, 28), font=f_body)
            draw.rectangle([(w - 150, y + 115), (w - 45, y + 160)], fill=(220, 38, 38))
            draw.text((w - 98, y + 128), "Retry", fill=(255, 255, 255), font=f_btn, anchor="mt")
            y += 200
        elif kind == "card":
            draw.rectangle([(25, y), (w - 25, y + 90)], fill=(255, 255, 255), outline=(225, 228, 238), width=1)
            draw.text((45, y + 20), val1, fill=(30, 35, 45), font=get_font(18, bold=True))
            draw.text((45, y + 50), val2, fill=(100, 105, 115), font=f_body)
            y += 105
        elif kind == "info":
            draw.text((30, y), val1, fill=(70, 75, 85), font=f_body)
            y += 30

    img.save(DATA_DIR / filename, "JPEG", quality=90)

def generate_screenshots():
    scrs = [
        ("scr_001.jpg", "System Warning", [("dialog", "Battery Low", "10% battery remaining. Plug in your charger soon.")]),
        ("scr_002.jpg", "Storage Manager", [("dialog", "Storage Space Running Out", "Only 450 MB available. Some apps may stop working.")]),
        ("scr_003.jpg", "Permission Request", [("dialog", "Location Permission", "Allow Ronie to access your location?")]),
        ("scr_004.jpg", "Network Alert", [("error_box", "Connection Error (504)", "Gateway timeout: server took too long to respond.")]),
        ("scr_005.jpg", "Wi-Fi Settings", [
            ("card", "Connected: Home_5G_Fiber", "Signal: Excellent  ·  IP: 192.168.1.45"),
            ("card", "Office_Guest", "Signal: Fair  ·  Secured (WPA2)"),
        ]),
        ("scr_006.jpg", "Order Status", [("card", "Out for Delivery", "Package arriving today by 6 PM via courier Rohan.")]),
        ("scr_007.jpg", "Calculator", [
            ("card", "1024 * 8", "= 8,192"),
            ("info", "Previous: 256 * 4 = 1,024", ""),
        ]),
        ("scr_008.jpg", "Clock & Alarms", [
            ("card", "06:30 AM", "Morning Run  ·  Mon, Tue, Wed, Thu, Fri"),
            ("card", "08:15 AM", "Work Standup  ·  Weekdays"),
        ]),
        ("scr_009.jpg", "Weather Forecast", [
            ("card", "Currently: 24°C", "Partly Cloudy  ·  Humidity: 68%"),
            ("card", "Today's High / Low", "High 28°C  ·  Low 19°C"),
        ]),
        ("scr_010.jpg", "Bluetooth Devices", [
            ("card", "Sony WH-1000XM5", "Connected (Audio)  ·  80% battery"),
            ("card", "Pixel Watch 3", "Connected"),
        ]),
        ("scr_011.jpg", "Pull Request #42", [("card", "PR #42: Add vision support", "Status: All checks passed  ·  2 approvals")]),
        ("scr_012.jpg", "Two-Factor Auth", [("dialog", "Enter Verification Code", "6-digit code sent to phone ending in 9921.")]),
        ("scr_013.jpg", "Music Player", [
            ("card", "Bohemian Rhapsody", "Artist: Queen  ·  Album: A Night at the Opera"),
            ("info", "Playing: 3:15 / 5:55", ""),
        ]),
        ("scr_014.jpg", "Play Store App Info", [("card", "Signal Private Messenger", "Size: 48 MB  ·  Rating: 4.6 stars  ·  100M+ downloads")]),
        ("scr_015.jpg", "Navigation Route", [("card", "In 500m", "Turn right onto MG Road  ·  Speed limit: 50 km/h")]),
    ]
    for f, t, widgets in scrs:
        render_ui_screenshot(f, t, widgets)

def generate_tool_and_honesty_images():
    # 6 Image + Tool images
    render_text_doc("tls_001.jpg", "Indie Rock Night Live Concert", [
        ("Event Date:", "Friday 14 November 2026"),
        ("Showtime:", "8:00 PM (Doors 7 PM)"),
        ("Venue:", "Starlight Club, Downtown"),
        ("Featuring:", "The Velvet Tones & Neon Sunset"),
        ("Tickets:", "$25 at the door"),
    ], "EVENT FLYER & TICKETS")

    render_text_doc("tls_002.jpg", "Dental Care Appointment", [
        ("Patient:", "Raunak"),
        ("Clinic:", "Dr. Dave Dental Specialists"),
        ("Date & Time:", "2026-11-12 at 15:00 (3:00 PM)"),
        ("Service:", "Routine Cleaning & Consultation"),
        ("Contact:", "555-0199"),
    ], "APPOINTMENT REMINDER CARD")

    render_text_doc("tls_003.jpg", "Water Utility Invoice", [
        ("Account ID:", "WTR-39201"),
        ("Service Address:", "142 Elm Street"),
        ("Due Date:", "2026-11-20"),
        ("Total Amount Due:", "Rs 1500.00"),
        ("Notice:", "Pay before due date to avoid late fee"),
    ], "MONTHLY UTILITY BILL")

    render_text_doc("tls_004.jpg", "Birthday Party Invitation", [
        ("Guest of Honor:", "Maya's 30th Birthday"),
        ("When:", "Saturday 14 November 2026 at 7 PM"),
        ("Where:", "Breeze Rooftop Cafe"),
        ("Dress Code:", "Casual Chic"),
        ("RSVP:", "By Thursday Nov 12"),
    ], "PARTY INVITATION")

    render_text_doc("tls_005.jpg", "Flight Itinerary Notification", [
        ("Flight Number:", "Indigo 6E-204"),
        ("Departure Date:", "2026-11-18"),
        ("Departure Time:", "09:30 AM"),
        ("Origin:", "DEL Terminal 3"),
        ("Destination:", "BLR Terminal 1"),
    ], "BOARDING PASS REMINDER")

    render_text_doc("tls_006.jpg", "Engineering Standup Schedule", [
        ("Meeting:", "Daily Core Team Standup"),
        ("Time:", "10:00 AM every weekday"),
        ("Duration:", "15 minutes"),
        ("Link:", "meet.corp/standup"),
    ], "TEAM CALENDAR MEMO")

    # 4 Honesty images
    # 1. Heavily blurred text
    img_blur = Image.new("RGB", (480, 240), (250, 250, 250))
    d_b = ImageDraw.Draw(img_blur)
    d_b.text((50, 90), "SECRET PASSWORD XYZ123", fill=(30, 30, 30), font=get_font(28, bold=True))
    img_blur = img_blur.filter(ImageFilter.GaussianBlur(radius=18))
    img_blur.save(DATA_DIR / "hns_001.jpg", "JPEG", quality=90)

    # 2. Cut-off receipt
    rec_cut = render_receipt(99, "Corner Bakery", "2026-11-04", "INR", [("Croissant", 1, 120.0)], 0.05)
    # Crop to cut off the total
    orig_cut = Image.open(DATA_DIR / "rec_100.jpg")
    cropped = orig_cut.crop((0, 0, 480, 280))
    cropped.save(DATA_DIR / "hns_002.jpg", "JPEG", quality=90)
    # Remove temporary rec_100.jpg
    if (DATA_DIR / "rec_100.jpg").exists():
        (DATA_DIR / "rec_100.jpg").unlink()

    # 3. Abstract pattern / geometric shapes
    img_abs = Image.new("RGB", (400, 400), (240, 245, 250))
    d_a = ImageDraw.Draw(img_abs)
    d_a.rectangle([(50, 50), (200, 200)], fill=(70, 130, 220), outline=(30, 60, 120), width=4)
    d_a.ellipse([(180, 180), (350, 350)], fill=(220, 80, 80), outline=(140, 30, 30), width=4)
    d_a.polygon([(100, 320), (180, 220), (240, 340)], fill=(80, 180, 100))
    img_abs.save(DATA_DIR / "hns_003.jpg", "JPEG", quality=90)

    # 4. Medicine label
    w, h = 520, 340
    img_med = Image.new("RGB", (w, h), (255, 255, 255))
    d_m = ImageDraw.Draw(img_med)
    d_m.rectangle([(15, 15), (w - 15, h - 15)], outline=(40, 120, 200), width=3)
    d_m.rectangle([(15, 15), (w - 15, 75)], fill=(40, 120, 200))
    d_m.text((30, 30), "HEALTHCARE PHARMACEUTICALS", fill=(255, 255, 255), font=get_font(18, bold=True))
    d_m.text((35, 100), "Paracetamol Tablets IP", fill=(20, 20, 30), font=get_font(28, bold=True))
    d_m.text((35, 150), "Strength: 500 mg per tablet", fill=(40, 40, 50), font=get_font(20, bold=True))
    d_m.text((35, 195), "Quantity: 10 Tablets  ·  Batch: PCM-4091", fill=(80, 80, 90), font=get_font(16))
    d_m.text((35, 235), "Mfg Date: 10/2026   Expiry: 09/2029", fill=(80, 80, 90), font=get_font(16))
    d_m.text((35, 280), "Keep in a cool and dry place away from children.", fill=(110, 110, 120), font=get_font(14))
    img_med.save(DATA_DIR / "hns_004.jpg", "JPEG", quality=92)

# 20 Photos from Wikimedia Commons CC0 / Public Domain
WIKIMEDIA_PHOTOS = [
    ("pho_001.jpg", "https://upload.wikimedia.org/wikipedia/commons/d/da/Taj-Mahal.jpg", "Taj Mahal", "CC BY-SA 4.0 / Public Domain"),
    ("pho_002.jpg", "https://upload.wikimedia.org/wikipedia/commons/8/85/Tour_Eiffel_Wikimedia_Commons_%28cropped%29.jpg", "Eiffel Tower", "CC BY-SA 3.0"),
    ("pho_003.jpg", "https://upload.wikimedia.org/wikipedia/commons/b/bd/Golden_Retriever_Dukedestiny01_drvd.jpg", "Golden Retriever", "CC BY-SA 3.0"),
    ("pho_004.jpg", "https://upload.wikimedia.org/wikipedia/commons/1/15/Red_Apple.jpg", "Red Apple", "CC BY-SA 2.0"),
    ("pho_005.jpg", "https://upload.wikimedia.org/wikipedia/commons/a/a3/Eq_it-na_pizza-margherita_sep2005_sml.jpg", "Pizza Margherita", "CC BY-SA 2.0"),
    ("pho_006.jpg", "https://upload.wikimedia.org/wikipedia/commons/4/40/Sunflower_sky_backdrop.jpg", "Sunflower", "CC BY-SA 3.0"),
    ("pho_007.jpg", "https://upload.wikimedia.org/wikipedia/commons/7/7b/Stop_sign.png", "Stop Sign", "Public Domain"),
    ("pho_008.jpg", "https://upload.wikimedia.org/wikipedia/commons/3/37/African_Bush_Elephant.jpg", "African Bush Elephant", "CC BY-SA 2.0"),
    ("pho_009.jpg", "https://upload.wikimedia.org/wikipedia/commons/4/45/A_small_cup_of_coffee.JPG", "Cup of Coffee", "CC BY-SA 2.0"),
    ("pho_010.jpg", "https://upload.wikimedia.org/wikipedia/commons/9/93/Clock_Tower_-_Palace_of_Westminster%2C_London_-_May_2007.jpg", "Big Ben", "CC BY-SA 3.0"),
    ("pho_011.jpg", "https://upload.wikimedia.org/wikipedia/commons/5/56/Tiger.50.jpg", "Tiger", "CC BY-SA 3.0"),
    ("pho_012.jpg", "https://upload.wikimedia.org/wikipedia/commons/e/eb/Traffic_lights_red.jpg", "Traffic Light Red", "CC BY-SA 3.0"),
    ("pho_013.jpg", "https://upload.wikimedia.org/wikipedia/commons/d/d8/Colosseum_in_Rome-April_2007-1-_copie_2B.jpg", "Colosseum", "CC BY-SA 2.5"),
    ("pho_014.jpg", "https://upload.wikimedia.org/wikipedia/commons/b/ba/Red_rose.jpg", "Red Rose", "CC BY-SA 3.0"),
    ("pho_015.jpg", "https://upload.wikimedia.org/wikipedia/commons/0/0b/Cat_poster_1.jpg", "Domestic Cat", "CC BY-SA 3.0"),
    ("pho_016.jpg", "local_hindi_sign", "Hindi Silence Signboard", "Public Domain / Custom Rendered"),
    ("pho_017.jpg", "https://upload.wikimedia.org/wikipedia/commons/c/c3/UK_traffic_sign_670.svg.png", "Speed limit 50 sign", "Public Domain"),
    ("pho_018.jpg", "https://upload.wikimedia.org/wikipedia/commons/8/8a/Banana-Single.jpg", "Banana", "CC BY-SA 3.0"),
    ("pho_019.jpg", "https://upload.wikimedia.org/wikipedia/commons/0/07/Emperor_Penguin_Manchot_empereur.jpg", "Emperor Penguin", "CC BY-SA 3.0"),
    ("pho_020.jpg", "https://upload.wikimedia.org/wikipedia/commons/f/f6/Cactus_flower.jpg", "Cactus", "CC BY-SA 3.0"),
]

def render_hindi_sign(filename):
    w, h = 500, 300
    img = Image.new("RGB", (w, h), (240, 242, 245))
    draw = ImageDraw.Draw(img)
    draw.rectangle([(20, 20), (w - 20, h - 20)], fill=(25, 45, 120), outline=(220, 220, 220), width=3)
    f_hi = get_indic_font(42)
    f_en = get_font(24, bold=True)
    draw.text((w // 2, 80), "कृपया शांत रहें", fill=(255, 255, 255), font=f_hi, anchor="mt")
    draw.text((w // 2, 175), "PLEASE MAINTAIN SILENCE", fill=(255, 220, 0), font=f_en, anchor="mt")
    img.save(DATA_DIR / filename, "JPEG", quality=90)

def download_photos():
    import time
    sources_doc = ["# Wikimedia Commons Sources & Licences\n\nAll photos are CC0, Creative Commons or Public Domain:\n\n| File | Subject | License | Source URL |\n|---|---|---|---|"]
    for filename, url, subject, lic in WIKIMEDIA_PHOTOS:
        out_path = DATA_DIR / filename
        if url == "local_hindi_sign":
            render_hindi_sign(filename)
            sources_doc.append(f"| `{filename}` | {subject} | {lic} | Local Rendered |")
            continue
        # Check if already a valid image
        valid = False
        if out_path.exists() and out_path.stat().st_size > 1000:
            try:
                Image.open(out_path).verify()
                valid = True
            except Exception:
                valid = False
        if not valid:
            print(f"Downloading {filename}: {subject}...")
            time.sleep(2.0)
            cmd = ["curl.exe", "-s", "-L", "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) RoniePocket/1.0", "-o", str(out_path), url]
            subprocess.run(cmd, check=True)
            try:
                im = Image.open(out_path).convert("RGB")
                im.thumbnail((1024, 1024))
                im.save(out_path, "JPEG", quality=88)
                print(f"  Saved {filename}: {im.size}")
            except Exception as e:
                print(f"  Warning on {filename}: {e}")
        sources_doc.append(f"| `{filename}` | {subject} | {lic} | {url} |")

    (Path(__file__).parent / "SOURCES.md").write_text("\n".join(sources_doc) + "\n", encoding="utf-8")

if __name__ == "__main__":
    print("1. Generating 20 receipts...")
    generate_receipts()
    print("2. Generating 15 documents...")
    generate_documents()
    print("3. Generating 15 screenshots...")
    generate_screenshots()
    print("4. Generating 6 tool + 4 honesty images...")
    generate_tool_and_honesty_images()
    print("5. Downloading 20 photos...")
    download_photos()
    print(f"All images generated in {DATA_DIR}")
