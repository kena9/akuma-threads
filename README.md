# Akuma Threads 🧵

> A full-stack e-commerce web application for an anime artist clothing brand, built with Java Spring Boot and MySQL.

![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=java)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-green?style=flat-square&logo=springboot)
![MySQL](https://img.shields.io/badge/MySQL-8.0-blue?style=flat-square&logo=mysql)
![Thymeleaf](https://img.shields.io/badge/Thymeleaf-3.x-darkgreen?style=flat-square)
![AWS](https://img.shields.io/badge/AWS-Elastic_Beanstalk-orange?style=flat-square&logo=amazonaws)

---

## Overview

Akuma Threads is a custom-built e-commerce platform designed for an independent anime artist brand. Customers can browse original anime-inspired clothing, manage a cart, and place orders. Store administrators manage inventory and order fulfillment through a dedicated dashboard.

Built as a Capstone Project (Summer 2026) — developed solo by **Kenaket Edjeta**.

---

## Features

### Customer Side
- Browse product catalog with category filters and keyword search
- View detailed product pages (images, sizes, price)
- Register / log in with secure authentication
- Add items to cart, update quantities, remove items
- Checkout with shipping details
- View order history and status

### Admin Dashboard
- Add, edit, and delete products with image upload
- Manage inventory levels per product variant (size/stock)
- View all customer orders
- Update order status (Pending → Processing → Shipped → Delivered)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.2.5 |
| Security | Spring Security 6 |
| ORM | Spring Data JPA / Hibernate |
| Database | MySQL 8 |
| Frontend | Thymeleaf + HTML5 + CSS3 + JavaScript |
| Build Tool | Maven |
| Version Control | Git + GitHub |
| API Testing | Postman |
| Deployment | AWS Elastic Beanstalk + RDS |

---

## Getting Started (Local Development)

### Prerequisites
- Java JDK 21
- Maven 3.9+
- MySQL 8.0
- IntelliJ IDEA (recommended)

### Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/kena9/akuma-threads.git
   cd akuma-threads
   ```

2. **Create the database**
   ```sql
   CREATE DATABASE akuma_threads;
   ```

3. **Configure credentials**

   Open `src/main/resources/application.properties` and update:
   ```properties
   spring.datasource.password=your_mysql_password
   ```

4. **Run the application**
   ```bash
   ./mvnw spring-boot:run
   ```

5. **Open in browser**
   ```
   http://localhost:8080
   ```

> Tables are created automatically on first run via `spring.jpa.hibernate.ddl-auto=update`

---

## Project Structure

```
src/main/java/com/akumathreads/
├── config/          # Spring Security configuration
├── controller/      # MVC controllers (shop, cart, checkout, admin)
├── model/           # JPA entities (User, Product, Order, CartItem...)
├── repository/      # Spring Data JPA repositories
├── service/         # Business logic layer
└── AkumaThreadsApplication.java
```

---

## API / Routes

| Route | Method | Access | Description |
|---|---|---|---|
| `/` | GET | Public | Home page |
| `/shop` | GET | Public | Product catalog |
| `/shop/{id}` | GET | Public | Product detail |
| `/register` | GET/POST | Guest | Create account |
| `/login` | GET/POST | Guest | Log in |
| `/cart` | GET | Customer | View cart |
| `/cart/add` | POST | Customer | Add to cart |
| `/checkout` | GET/POST | Customer | Place order |
| `/orders` | GET | Customer | Order history |
| `/admin/products` | GET | Admin | Manage products |
| `/admin/orders` | GET | Admin | Manage orders |

---

## Deployment (AWS)

Production environment runs on:
- **AWS Elastic Beanstalk** — Java 21 platform
- **AWS RDS** — MySQL 8.0 (db.t3.micro)

### Prerequisites

1. AWS account with an IAM user that has `ElasticBeanstalkFullAccess` and `AmazonRDSFullAccess`
2. Elastic Beanstalk application named `akuma-threads` with environment `akuma-threads-prod` (Java 21 platform)
3. RDS MySQL 8 instance (endpoint, username, password)
4. GitHub repository secrets (Settings → Secrets → Actions):
   - `AWS_ACCESS_KEY_ID`
   - `AWS_SECRET_ACCESS_KEY`

### Environment Variables (Elastic Beanstalk Console)

In the EB console → Configuration → Environment properties, set:

| Key | Example value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL` | `jdbc:mysql://your-rds-endpoint:3306/akuma_threads?useSSL=true&serverTimezone=UTC` |
| `DB_USERNAME` | `admin` |
| `DB_PASSWORD` | `your-rds-password` |

### Deploy Steps

```bash
# 1. Ensure all tests pass locally
./mvnw test

# 2. Build the production JAR
./mvnw package -DskipTests -B

# 3. Push to main — deploy does NOT auto-trigger on push
git push origin main

# 4. Trigger deployment manually
# GitHub → repo → Actions → "Deploy to AWS Elastic Beanstalk" → Run workflow
```

> The GitHub Actions workflow (`.github/workflows/deploy.yml`) is set to `workflow_dispatch`,
> meaning it only runs when you manually click "Run workflow" in the GitHub Actions UI.
> This prevents accidental production deploys while AWS credentials are being configured.

### First Deploy Checklist

- [ ] RDS instance is publicly accessible or in the same VPC as EB
- [ ] Security group allows EB → RDS on port 3306
- [ ] `spring.jpa.hibernate.ddl-auto=validate` in `application-prod.properties` (schema must pre-exist)
- [ ] Run schema migration manually on RDS before first deploy:
  `./mvnw spring-boot:run -Dspring.profiles.active=dev` locally then export schema
- [ ] Confirm environment variable `SPRING_PROFILES_ACTIVE=prod` is set in EB console

---

## Roadmap

- [x] Project scaffolding and data models
- [ ] User authentication (register / login)
- [ ] Product catalog with search and filters
- [ ] Shopping cart
- [ ] Checkout and order management
- [ ] Admin dashboard
- [ ] AWS deployment
- [ ] Product image upload to S3

---

## License

This project is for academic and portfolio purposes.  
© 2026 Kenaket Edjeta — Akuma Threads
