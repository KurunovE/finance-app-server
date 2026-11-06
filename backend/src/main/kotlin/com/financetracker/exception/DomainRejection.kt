package com.financetracker.exception

class DomainRejection(val code: String) : RuntimeException(code)
