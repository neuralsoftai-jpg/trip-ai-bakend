# 🌍 AI Trip Planner

> An AI-powered full-stack trip planning app. Enter your source, destination, travel days, group size, and vehicle type — get instant itinerary, weather forecast, fuel cost estimate, packing list, budget, and route optimization.

![Tech Stack](https://img.shields.io/badge/Backend-Spring%20Boot%203-brightgreen)
![Tech Stack](https://img.shields.io/badge/Frontend-Next.js%2016-black)
![Tech Stack](https://img.shields.io/badge/AI-Google%20Gemini-blue)
![Tech Stack](https://img.shields.io/badge/Routing-OSRM-orange)

---

## ✨ Features

| Feature | Description |
|---|---|
| 🗺️ **Route Optimization** | TSP-based nearest-neighbour algorithm |
| 🌤️ **Weather Forecast** | Real-time OpenWeatherMap day-by-day forecast |
| ⛽ **Fuel Cost Calculator** | Per-person fuel split by vehicle type |
| 🌱 **Carbon Footprint** | IPCC-based CO₂ emission estimation |
| 🎒 **Smart Packing List** | AI-generated destination-aware checklist |
| 💰 **Budget Estimator** | Budget/Luxury tier breakdown |
| 🚨 **Emergency Contacts** | Local emergency numbers for destination |
| 💬 **AI Chatbot** | Powered by Google Gemini |
| 📄 **PDF Download** | Full trip report as PDF |

---

## 🛠️ Tech Stack

### Backend (Spring Boot 3)
- **Java 21** + Spring Boot 3.2.5
- **Google Gemini API** — AI vibe, chatbot, packing list
- **OpenWeatherMap API** — Weather forecast + geocoding
- **OSRM** — Open-source road routing
- **Redis** — Response caching (optional, gracefully degrades)
- **Resilience4j** — Circuit breakers for external APIs
- **Bucket4j** — Rate limiting
- **iText PDF** — PDF generation

### Frontend (Next.js 16)
- **Next.js 16** (App Router) + TypeScript
- **Tailwind CSS v4** + Framer Motion
- **Zustand** — State management
- **Lucide React** — Icons

---

## 🚀 Quick Start (Local Development)

### Prerequisites
- Java 21+
- Node.js 18+
- Maven (or use `./mvnw`)

### 1. Clone the repo
```bash
git clone https://github.com/YOUR_USERNAME/trip-planner.git
cd trip-planner
```

### 2. Setup Backend API Keys
```bash
# Copy the example file
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml

# Edit and add your real API keys
```

In `application-local.yml`:
```yaml
api:
  openweathermap:
    api-key: YOUR_OWM_KEY      # https://openweathermap.org/api
  gemini:
    api-key: YOUR_GEMINI_KEY   # https://aistudio.google.com/app/apikey
```

### 3. Start Backend
```bash
# Windows
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local

# Mac/Linux
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
Backend runs at: `http://localhost:8080`

### 4. Setup Frontend
```bash
cd frontend
cp .env.example .env.local
npm install
npm run dev
```
Frontend runs at: `http://localhost:3000`

---

## 🌐 Production Deployment

### Option A: Railway (Backend) + Vercel (Frontend) — Recommended Free Tier

#### Backend → Railway
1. Go to [railway.app](https://railway.app) → New Project → Deploy from GitHub
2. Select this repo
3. Set **Root Directory**: `/` (project root)
4. Set **Build Command**: `./mvnw package -DskipTests`
5. Set **Start Command**: `java -jar target/trip-planner-1.0.0.jar`
6. Add **Environment Variables** in Railway dashboard:
   ```
   OWM_API_KEY=your_openweathermap_key
   GEMINI_API_KEY=your_gemini_key
   SPRING_PROFILES_ACTIVE=default
   ```
7. Copy the Railway deployment URL (e.g. `https://trip-planner.up.railway.app`)

#### Frontend → Vercel
1. Go to [vercel.com](https://vercel.com) → Import Project → Select this repo
2. Set **Root Directory**: `frontend`
3. Set **Framework**: Next.js
4. Add **Environment Variable** in Vercel dashboard:
   ```
   NEXT_PUBLIC_API_URL=https://your-railway-url.up.railway.app/api/v1/trip
   ```
5. Deploy!

---

### Option B: Render (Backend) + Vercel (Frontend)

#### Backend → Render
1. Go to [render.com](https://render.com) → New → Web Service
2. Connect GitHub repo
3. **Build Command**: `./mvnw package -DskipTests`
4. **Start Command**: `java -jar target/trip-planner-1.0.0.jar`
5. Add environment variables:
   ```
   OWM_API_KEY=your_key
   GEMINI_API_KEY=your_key
   ```
6. Free tier note: Render free tier spins down after inactivity (cold start ~30s)

---

## 🔐 Environment Variables Reference

### Backend (Railway / Render / any server)
| Variable | Required | Description |
|---|---|---|
| `OWM_API_KEY` | ✅ Yes | OpenWeatherMap API Key |
| `GEMINI_API_KEY` | ✅ Yes | Google Gemini API Key |
| `OSRM_BASE_URL` | ❌ Optional | Custom OSRM server (default: public demo) |
| `REDIS_HOST` | ❌ Optional | Redis host (default: localhost) |
| `REDIS_PORT` | ❌ Optional | Redis port (default: 6379) |

### Frontend (Vercel)
| Variable | Required | Description |
|---|---|---|
| `NEXT_PUBLIC_API_URL` | ✅ Yes | Backend API base URL |

---

## 📁 Project Structure

```
trip-planner/
├── src/main/java/com/tripplanner/
│   ├── client/          # External API clients (Gemini, OSRM, Weather)
│   ├── config/          # Spring configs (CORS, Redis, Rate Limit, Async)
│   ├── controller/      # REST controllers
│   ├── dto/             # Request/Response DTOs
│   ├── exception/       # Global error handling
│   └── service/         # Business logic
├── src/main/resources/
│   ├── application.yml          # Main config (env vars, no secrets)
│   └── application-local.yml.example  # Local dev template
├── frontend/
│   ├── src/app/         # Next.js App Router pages
│   ├── src/components/  # React components (atoms/molecules/organisms)
│   ├── src/services/    # API service layer
│   ├── src/store/       # Zustand state
│   └── src/types/       # TypeScript types
├── .gitignore
└── README.md
```

---

## 🔑 Getting API Keys (Free)

### OpenWeatherMap (Free)
1. Go to https://openweathermap.org/api
2. Sign up → API Keys → Copy your key
3. Free tier: 1,000 calls/day

### Google Gemini (Free)
1. Go to https://aistudio.google.com/app/apikey
2. Create API key
3. Free tier: 15 requests/minute, 1M tokens/day

---

## 📝 License

MIT License — feel free to use, modify, and deploy!

---

Built with ❤️ using Spring Boot + Next.js + Google Gemini
